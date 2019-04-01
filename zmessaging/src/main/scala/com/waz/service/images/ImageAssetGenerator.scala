/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.images

import android.content.Context
import android.graphics.{Bitmap => ABitmap}
import com.waz.log.LogSE._
import com.waz.bitmap.BitmapUtils.Mime
import com.waz.bitmap.{BitmapDecoder, BitmapUtils}
import com.waz.cache.{CacheEntry, CacheService, LocalData}
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.log.LogShow.SafeToLog
import com.waz.model
import com.waz.model.AssetMetaData.Image.Tag.Preview
import com.waz.model._
import com.waz.service.assets.AssetService
import com.waz.service.images.ImageLoader.Metadata
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.ui.MemoryImageCache
import com.waz.ui.MemoryImageCache.BitmapRequest
import com.waz.ui.MemoryImageCache.BitmapRequest.{Regular, Single}
import com.waz.utils._
import com.waz.utils.wrappers.Bitmap

import scala.concurrent.Future

class ImageAssetGenerator(context: Context, cache: CacheService, loader: ImageLoader, imageCache: MemoryImageCache, bitmapLoader: BitmapDecoder) extends DerivedLogTag {

  import com.waz.service.images.ImageAssetGenerator._

  implicit private val dispatcher  = Threading.ImageDispatcher

  lazy val saveDir = AssetService.assetDir(context)

  /**
    * Generate wire asset from local asset data. It will use the same AssetId generated by the local asset data in generating the returned
    * asset data. So be sure that it is not used for another asset, as it will be overridden.
    **/
  def generateWireAsset(asset: AssetData, profilePicture: Boolean): CancellableFuture[AssetData] = {
    verbose(l"generateWireAsset: $asset, profilePicture?: $profilePicture")
    loader.loadRawImageData(asset) flatMap {
      case Some(data) =>
        loader.getImageMetadata(data) flatMap { meta => generateAssetData(asset, Left(data), meta, if (profilePicture) SelfOptions else ImageOptions) }
      case _ =>
        CancellableFuture.failed(new IllegalArgumentException(s"ImageAsset could not be added to cache: $asset"))
    }
  }

  //used for creating small previews for user profile assets for the web team, based off of some input asset. It will
  //create a new AssetData representing the preview image
  def generateSmallProfile(asset: AssetData): CancellableFuture[AssetData] = {
    loader.loadRawImageData(asset) flatMap {
      case Some(data) =>
        loader.getImageMetadata(data) flatMap { meta => generateAssetData(AssetData.newImageAsset(AssetId(), Preview), Left(data), meta, SmallProfileOptions) }
      case _ =>
        CancellableFuture.failed(new IllegalArgumentException(s"ImageAsset could not be added to cache: $asset"))
    }
  }

  def generateAssetData(asset: AssetData, input: Either[LocalData, ABitmap], meta: Metadata, co: CompressionOptions): CancellableFuture[AssetData] = {
    generateImageData(asset, co, input, meta) flatMap {
      case (file, m) =>
        verbose(l"generated image, size: ${input.fold(_.length, _.getByteCount)}, meta: $m")
        if (shouldRecode(file, m, co)) recode(asset.id, file, co, m)
        else CancellableFuture.successful((file, m))
    } map {
      case (file, m) =>
        val size = file.length
        verbose(l"final image, size: $size, meta: $m")

        asset.copy(
          mime = com.waz.model.Mime(m.mimeType),
          sizeInBytes = size,
          metaData = Some(AssetMetaData.Image(Dim2(m.width, m.height), asset.tag)),
          data = None //can remove data as we now have a cached version
        )
    }
  }

  private def generateImageData(asset: AssetData, options: CompressionOptions, input: Either[LocalData, ABitmap], meta: Metadata) = {

    def loadScaled(w: Int, h: Int, crop: Boolean) = {
      val minWidth = if (crop) math.max(w, w * meta.width / meta.height) else w
      val sampleSize = BitmapUtils.computeInSampleSize(minWidth, meta.width)
      val memoryNeeded = (w * h) + (meta.width / sampleSize * meta.height / sampleSize) * 4
      imageCache.reserve(asset.id, options.req, memoryNeeded)
      input.fold(ld => bitmapLoader(() => ld.inputStream, sampleSize, meta.orientation).map(Bitmap.toAndroid), CancellableFuture.successful) map { image =>
        if (crop) {
          verbose(l"cropping to $w")
          BitmapUtils.cropRect(image, w)
        } else if (image.getWidth > w) {
          verbose(l"scaling to $w, $h")
          BitmapUtils.scale(image, w, h)
        } else image
      }
    }

    def generateScaled(): CancellableFuture[(CacheEntry, Metadata)] = {
      val (w, h) = options.calculateScaledSize(meta.width, meta.height)
      verbose(l"calculated scaled size: ($w, $h) for $meta and $options")
      loadScaled(w, h, options.cropToSquare) flatMap { image =>
        verbose(l"loaded scaled: (${image.getWidth}, ${image.getHeight})")
        save(image)
      }
    }

    def save(image: ABitmap): CancellableFuture[(CacheEntry, Metadata)] = {
      imageCache.add(asset.id, options.req, image)
      saveImage(asset.cacheKey, image, meta.mimeType, options)
    }

    if (options.shouldScaleOriginalSize(meta.width, meta.height)) generateScaled()
    else input.fold(
      local => cache.addStream(asset.cacheKey, local.inputStream, cacheLocation = Some(saveDir), mime = model.Mime(meta.mimeType)).map((_, meta)).lift,
      image => save(image))
  }

  private def saveFormat(mime: String, forceLossy: Boolean) =
    if (!forceLossy && mime == Mime.Png) ABitmap.CompressFormat.PNG
    else ABitmap.CompressFormat.JPEG

  private def saveImage(key: CacheKey, image: ABitmap, mime: String, options: CompressionOptions): CancellableFuture[(CacheEntry, Metadata)] =
    cache.createForFile(key, cacheLocation = Some(saveDir)).flatMap(saveImage(_, image, mime, options)).lift

  private def saveImage(file: CacheEntry, image: ABitmap, mime: String, options: CompressionOptions): Future[(CacheEntry, Metadata)] = {
    val format = saveFormat(mime, options.forceLossy)
    val (len, compressed) = IoUtils.counting(file.outputStream) { os => image.compress(format, options.quality, os) }
    file.updatedWithLength(len).map(ce => (ce, Metadata(image.getWidth, image.getHeight, if (format == ABitmap.CompressFormat.PNG) Mime.Png else Mime.Jpg)))
  }

  private[images] def shouldRecode(file: LocalData, meta: Metadata, opts: CompressionOptions) = {
    val size = file.length
    opts.recodeMimes(meta.mimeType) ||
    meta.mimeType != Mime.Gif && size > opts.byteCount ||
    size > MaxGifSize ||
    meta.isRotated
  }

  private def recode(id: AssetId, file: CacheEntry, options: CompressionOptions, meta: Metadata) = {
    verbose(l"recode asset $id with opts: $options")

    def load = {
      imageCache.reserve(id, options.req, meta.width, if (meta.isRotated) 2 * meta.height else meta.height)
      bitmapLoader(() => file.inputStream, 1, meta.orientation)
    }

    imageCache(id, options.req, meta.width, load).future.flatMap(bmp => saveImage(file, Bitmap.toAndroid(bmp), Mime.Jpg, options)).lift
  }
}

object ImageAssetGenerator {

  val PreviewSize = 64
  val SmallProfileSize = 280
  val MediumSize = 1448

  val PreviewCompressionQuality      = 30
  val JpegCompressionQuality         = 75
  val SmallProfileCompressionQuality = 70

  val MaxImagePixelCount = 1.3 * 1448 * 1448
  val MaxGifSize         = 5 * 1024 * 1024

  val PreviewRecodeMimes = CompressionOptions.DefaultRecodeMimes + Mime.Gif

  val PreviewOptions = CompressionOptions(1024, PreviewSize, PreviewCompressionQuality, forceLossy = true, cropToSquare = false, Single(PreviewSize), PreviewRecodeMimes)
  val MediumOptions  = CompressionOptions(310 * 1024, MediumSize, JpegCompressionQuality, forceLossy = false, cropToSquare = false, Regular(MediumSize))

  val SmallProfileOptions  = new CompressionOptions(15 * 1024, SmallProfileSize, SmallProfileCompressionQuality, forceLossy = true, cropToSquare = true, Single(SmallProfileSize), PreviewRecodeMimes)
  val MediumProfileOptions = MediumOptions.copy(recodeMimes = PreviewRecodeMimes)

  val ImageOptions = MediumOptions
  val SelfOptions  = MediumProfileOptions
}

case class CompressionOptions(byteCount: Int,
                              dimension: Int,
                              quality: Int,
                              forceLossy: Boolean,
                              cropToSquare: Boolean,
                              req: BitmapRequest,
                              recodeMimes: Set[String] = CompressionOptions.DefaultRecodeMimes) extends SafeToLog {

  val maxPixelCount = 1.3 * dimension * dimension

  def shouldScaleOriginalSize(width: Int, height: Int): Boolean =
    width * height > maxPixelCount || (cropToSquare && width != height)

  def calculateScaledSize(origWidth: Int, origHeight: Int): (Int, Int) = {
    if (origWidth < 1 || origHeight < 1) (1, 1)
    else if (cropToSquare) {
      val size = math.min(dimension, math.min(origWidth, origHeight))
      (size, size)
    } else {
      val scale = math.sqrt((dimension * dimension).toDouble / (origWidth * origHeight))
      val width = math.ceil(scale * origWidth).toInt
      (width, (width.toDouble / origWidth * origHeight).round.toInt)
    }
  }

  def getOutputFormat(mime: String) =
    if (!forceLossy && mime == Mime.Png) ABitmap.CompressFormat.PNG
    else ABitmap.CompressFormat.JPEG
}

object CompressionOptions {

  // set of mime types that should be recoded to Jpeg before uploading
  val DefaultRecodeMimes = Set(Mime.WebP, Mime.Unknown, Mime.Tiff, Mime.Bmp)
}
