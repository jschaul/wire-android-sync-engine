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
package com.waz.sync.handler

import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.api.impl.ErrorResponse
import com.waz.api.impl.ErrorResponse.internalError
import com.waz.cache.CacheService
import com.waz.content.{MembersStorage, MessagesStorage}
import com.waz.model.AssetData.{ProcessingTaskKey, UploadTaskKey}
import com.waz.model.GenericContent.{Ephemeral, Knock, Location, MsgEdit}
import com.waz.model.GenericMessage.TextMessage
import com.waz.model._
import com.waz.model.errors._
import com.waz.model.sync.ReceiptType
import com.waz.service.assets2.Asset.{General, Image, UploadGeneral}
import com.waz.service.assets2.{AssetService, _}
import com.waz.service.conversation.ConversationsContentUpdater
import com.waz.service.messages.{MessagesContentUpdater, MessagesService}
import com.waz.service.otr.OtrClientsService
import com.waz.service.tracking.TrackingService
import com.waz.service.{ErrorsService, Timeouts}
import com.waz.sync.SyncHandler.RequestInfo
import com.waz.sync.SyncResult.Failure
import com.waz.sync.client.ErrorOrResponse
import com.waz.sync.otr.OtrSyncHandler
import com.waz.sync.{SyncResult, SyncServiceHandle}
import com.waz.threading.CancellableFuture
import com.waz.utils._
import com.waz.znet2.http.ResponseCode

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.concurrent.duration.FiniteDuration

class MessagesSyncHandler(selfUserId: UserId,
                          service:    MessagesService,
                          msgContent: MessagesContentUpdater,
                          clients:    OtrClientsService,
                          otrSync:    OtrSyncHandler,
                          convs:      ConversationsContentUpdater,
                          storage:    MessagesStorage,
                          assetSync:  AssetSyncHandler,
                          sync:       SyncServiceHandle,
                          assets:     AssetService,
                          assetStorage: AssetStorage,
                          uploadAssetStorage: UploadAssetStorage,
                          cache:      CacheService,
                          members:    MembersStorage,
                          tracking:   TrackingService,
                          errors:     ErrorsService,
                          timeouts: Timeouts) {
  import com.waz.threading.Threading.Implicits.Background

  def postDeleted(convId: ConvId, msgId: MessageId): Future[SyncResult] =
    convs.convById(convId).flatMap {
      case Some(conv) =>
        val msg = GenericMessage(Uid(), Proto.MsgDeleted(conv.remoteId, msgId))
        otrSync
          .postOtrMessage(ConvId(selfUserId.str), msg)
          .map(SyncResult(_))
      case None =>
        successful(Failure("conversation not found"))
    }


  def postRecalled(convId: ConvId, msgId: MessageId, recalled: MessageId): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val msg = GenericMessage(msgId.uid, Proto.MsgRecall(recalled))
        otrSync.postOtrMessage(conv.id, msg).flatMap {
          case Left(e) => successful(SyncResult(e))
          case Right(time) =>
            msgContent
              .updateMessage(msgId)(_.copy(editTime = time, state = Message.Status.SENT))
              .map(_ => SyncResult.Success)
        }
      case None =>
        successful(Failure("conversation not found"))
    }

  def postReceipt(convId: ConvId, msgs: Seq[MessageId], userId: UserId, tpe: ReceiptType): Future[SyncResult] =
    convs.convById(convId) flatMap {
      case Some(conv) =>
        val (msg, recipients) = tpe match {
          case ReceiptType.Delivery         => (GenericMessage(msgs.head.uid, Proto.DeliveryReceipt(msgs))(Proto.DeliveryReceipt), Set(userId))
          case ReceiptType.Read             => (GenericMessage(msgs.head.uid, Proto.ReadReceipt(msgs))(Proto.ReadReceipt), Set(userId))
          case ReceiptType.EphemeralExpired => (GenericMessage(msgs.head.uid, Proto.MsgRecall(msgs.head)), Set(selfUserId, userId))
        }

        otrSync
          .postOtrMessage(conv.id, msg, Some(recipients), nativePush = false)
          .map(SyncResult(_))
      case None =>
        successful(Failure("conversation not found"))
    }

  def postMessage(convId: ConvId, id: MessageId, editTime: RemoteInstant)(implicit info: RequestInfo): Future[SyncResult] =
    storage.getMessage(id).flatMap { message =>
      message
        .fold(successful(None: Option[ConversationData]))(msg => convs.convById(msg.convId))
        .map(conv => (message, conv))
    }.flatMap {
      case (Some(msg), Some(conv)) =>
        postMessage(conv, msg, editTime).flatMap {
          case Right(time) =>
            verbose(s"postOtrMessage($msg) successful $time")
            for {
              _ <- service.messageSent(convId, msg.id, time)
              (prevLastTime, lastTime) <- msgContent.updateLocalMessageTimes(convId, msg.time, time)
                .map(_.lastOption.map { case (p, c) => (p.time, c.time)}.getOrElse((msg.time, time)))
              // update conv lastRead time if there is no unread message after the message that was just sent
              _ <- convs.storage.update(convId, c => if (!c.lastRead.isAfter(prevLastTime)) c.copy(lastRead = lastTime) else c)
              _ <- convs.updateLastEvent(convId, time)
            } yield SyncResult.Success

          case Left(error) =>
            for {
              _ <- error match {
                case ErrorResponse(ResponseCode.Forbidden, _, "unknown-client") =>
                  clients.onCurrentClientRemoved()
                case _ =>
                  Future.successful({})
              }
              syncResult = error match {
                case ErrorResponse(ErrorResponse.ConnectionErrorCode, _, _) =>
                  Failure.apply(error)
                case _ =>
                  SyncResult(error)
              }
              result <- syncResult match {
                case r: SyncResult.Failure =>
                  service
                    .messageDeliveryFailed(convId, msg, error)
                    .map(_ => r)
                case r =>
                  Future.successful(r)
              }
            } yield result
        }

      case (Some(msg), None) =>
        service
          .messageDeliveryFailed(msg.convId, msg, internalError("conversation not found"))
          .map(_ => Failure("postMessage failed, couldn't find conversation for msg"))

      case _ =>
        successful(Failure("postMessage failed, couldn't find either message or conversation"))
    }

  private def postMessage(conv: ConversationData, msg: MessageData, reqEditTime: RemoteInstant) = {

    def postTextMessage() = {
      val adjustedMsg = msg.adjustMentions(true).getOrElse(msg)

      val (gm, isEdit) =
        adjustedMsg.protos.lastOption match {
          case Some(m@GenericMessage(id, MsgEdit(ref, text))) if !reqEditTime.isEpoch =>
            (m, true) // will send edit only if original message was already sent (reqEditTime > EPOCH)
          case _ =>
            (TextMessage(adjustedMsg), false)
        }

      otrSync.postOtrMessage(conv.id, gm).flatMap {
        case Right(time) if isEdit =>
          // delete original message and create new message with edited content
          service.applyMessageEdit(conv.id, msg.userId, RemoteInstant(time.instant), gm) map {
            case Some(m) => Right(m)
            case _ => Right(msg.copy(time = RemoteInstant(time.instant)))
          }
        case Right(time) => successful(Right(msg.copy(time = time)))
        case Left(err) => successful(Left(err))
      }
    }

    import Message.Type._

    msg.msgType match {
      case _ if msg.isAssetMessage => Cancellable(UploadTaskKey(msg.assetId.get))(uploadAsset(conv, msg)).future
      case KNOCK => otrSync.postOtrMessage(conv.id, GenericMessage(msg.id.uid, msg.ephemeral, Proto.Knock(msg.expectsRead.getOrElse(false))))
      case TEXT | TEXT_EMOJI_ONLY => postTextMessage().map(_.map(_.time))
      case RICH_MEDIA =>
        postTextMessage().flatMap {
          case Right(m) => sync.postOpenGraphData(conv.id, m.id, m.editTime).map(_ => Right(m.time))
          case Left(err) => successful(Left(err))
        }
      case LOCATION =>
        msg.protos.headOption match {
          case Some(GenericMessage(id, loc: Location)) if msg.isEphemeral =>
            otrSync.postOtrMessage(conv.id, GenericMessage(id, Ephemeral(msg.ephemeral, loc)))
          case Some(proto) =>
            otrSync.postOtrMessage(conv.id, proto)
          case None =>
            successful(Left(internalError(s"Unexpected location message content: $msg")))
        }
      case tpe =>
        msg.protos.headOption match {
          case Some(proto) if !msg.isEphemeral =>
            verbose(s"sending generic message: $proto")
            otrSync.postOtrMessage(conv.id, proto)
          case Some(_) =>
            successful(Left(internalError(s"Can not send generic ephemeral message: $msg")))
          case None =>
            successful(Left(internalError(s"Unsupported message type in postOtrMessage: $tpe")))
        }
    }
  }

  private def uploadAsset(conv: ConversationData, msg: MessageData): ErrorOrResponse[RemoteInstant] = {
    import com.waz.model.errors._

    verbose(s"uploadAsset($conv, $msg)")

    def postAssetMessage(proto: GenericMessage, id: GeneralAssetId): CancellableFuture[RemoteInstant] = {
      for {
        time <- otrSync.postOtrMessage(conv.id, proto).flatMap {
          case Left(errorResponse) => Future.failed(errorResponse)
          case Right(time) => Future.successful(time)
        }.toCancellable
        _ <- msgContent.updateMessage(msg.id)(_.copy(protos = Seq(proto), time = time, assetId = Some(id))).toCancellable
      } yield time
    }

    //TODO Dean: Update asset status to UploadInProgress after posting original - what about images...?
    def postOriginal(rawAsset: UploadAsset[General]): CancellableFuture[RemoteInstant] =
      if (rawAsset.status != UploadAssetStatus.NotStarted) CancellableFuture.successful(msg.time)
      else rawAsset.details match {
        case _: Image => CancellableFuture.successful(msg.time)
        case _ => postAssetMessage(GenericMessage(msg.id.uid, msg.ephemeral, Proto.Asset(rawAsset, None, expectsReadConfirmation = msg.expectsRead.contains(true))), rawAsset.id)
      }

    def sendWithV3(uploadAssetOriginal: UploadAsset[UploadGeneral]): CancellableFuture[RemoteInstant] = {
      for {
        rawAssetWithMetadata <- uploadAssetOriginal.details match {
          case details: General => CancellableFuture.successful(uploadAssetOriginal.copy(details = details))
          case details => CancellableFuture.failed(FailedExpectationsError(s"We expect that metadata already extracted. Got $details"))
        }
        time <- postOriginal(rawAssetWithMetadata)
        uploadAssetOriginal <- uploadAssetOriginal.preview match {
          case Preview.NotReady => assets.createAndSavePreview(rawAssetWithMetadata).toCancellable
          case _ => CancellableFuture.successful(rawAssetWithMetadata)
        }
        previewAsset <- uploadAssetOriginal.preview match {
          case Preview.NotUploaded(uploadAssetPreviewId) =>
            for {
              previewAsset <- assets.uploadAsset(uploadAssetPreviewId)
              proto = GenericMessage(
                msg.id.uid,
                msg.ephemeral,
                Proto.Asset(uploadAssetOriginal, Some(previewAsset), expectsReadConfirmation = false)
              )
              _ <- postAssetMessage(proto, uploadAssetOriginal.id)
            } yield Some(previewAsset)
          case Preview.Uploaded(assetId) =>
            assetStorage.get(assetId).map(Some.apply).toCancellable
          case Preview.Empty =>
            CancellableFuture.successful(None)
          case Preview.NotReady =>
            CancellableFuture.failed(FailedExpectationsError("We should never get not ready preview in this place"))
        }
        _ <- (previewAsset match {
          case Some(p) => uploadAssetStorage.update(uploadAssetOriginal.id, _.copy(preview = Preview.Uploaded(p.id)))
          case None => Future.successful(())
        }).toCancellable
        asset <- assets.uploadAsset(uploadAssetOriginal.id)
        proto = GenericMessage(
          msg.id.uid,
          msg.ephemeral,
          Proto.Asset(asset, previewAsset, expectsReadConfirmation = msg.expectsRead.contains(true))
        )
        _ <- postAssetMessage(proto, asset.id)
      } yield time
    }

    //want to wait until asset meta and preview data is loaded before we send any messages
    for {
      _ <- AssetProcessing.get(ProcessingTaskKey(msg.assetId.get))
      rawAsset <- uploadAssetStorage.find(msg.assetId.collect { case id: UploadAssetId => id }.get).toCancellable
      result <- rawAsset match {
        case None =>
          CancellableFuture.successful(Left(internalError(s"no asset found for msg: $msg")))
        case Some(asset) if asset.status == UploadAssetStatus.Cancelled =>
          CancellableFuture.successful(Left(ErrorResponse.Cancelled))
        case Some(asset) =>
          verbose(s"Sending asset: $asset")
          sendWithV3(asset).map(Right.apply)
      }
    } yield result
  }

  private[waz] def messageSent(convId: ConvId, msg: MessageData, time: RemoteInstant) = {
    debug(s"otrMessageSent($convId. $msg, $time)")

    def updateLocalTimes(conv: ConvId, prevTime: RemoteInstant, time: RemoteInstant) =
      msgContent.updateLocalMessageTimes(conv, prevTime, time) flatMap { updated =>
        val prevLastTime = updated.lastOption.fold(prevTime)(_._1.time)
        val lastTime = updated.lastOption.fold(time)(_._2.time)
        // update conv lastRead time if there is no unread message after the message that was just sent
        convs.storage.update(conv,
          c => if (!c.lastRead.isAfter(prevLastTime)) c.copy(lastRead = lastTime) else c
        )
      }

    for {
      _ <- updateLocalTimes(convId, msg.time, time)
      _ <- convs.updateLastEvent(convId, time)
    } yield ()
  }

  def postAssetStatus(cid: ConvId, mid: MessageId, expiration: Option[FiniteDuration], statusToPost: UploadAssetStatus): Future[SyncResult] = {
    val result = for {
      //TODO Use new storage to avoid explicit 'Option.get'
      (conv, msg) <- convs.storage.get(cid).map(_.get) zip storage.get(mid).map(_.get)
      uploadAsset <- msg.assetId match {
        case Some(id: UploadAssetId) => uploadAssetStorage.get(id)
        case _ => Future.failed(FailedExpectationsError(s"We expect not uploaded asset id. Got ${msg.assetId}."))
      }
      uploadAssetPreview <- uploadAsset.preview match {
        case Preview.Uploaded(id) => assetStorage.find(id)
        case _ => Future.successful(None)
      }
      genericAsset <- uploadAsset.status match {
        case assetStatus if assetStatus == statusToPost =>
          Future.successful(Proto.Asset(uploadAsset.asInstanceOf[UploadAsset[General]], uploadAssetPreview, expectsReadConfirmation = false))
        case assetStatus =>
          Future.failed(FailedExpectationsError(s"We expect uploaded asset status $statusToPost. Got $assetStatus."))
      }
      message = GenericMessage(mid.uid, expiration, genericAsset)
      _ <- otrSync.postOtrMessage(conv.id, message)
      _ <- statusToPost match {
        case UploadAssetStatus.Cancelled => storage.remove(msg.id)
        case _ => Future.successful(())
      }
    } yield ()

    result.modelToEither.map(_.fold(SyncResult.apply, _ => SyncResult.Success))
  }
}
