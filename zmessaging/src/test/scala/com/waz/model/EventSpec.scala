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
package com.waz.model

import com.waz.model.Event.EventDecoder
import com.waz.model.otr.ClientId
import com.waz.service.PropertyKey
import com.waz.specs.AndroidFreeSpec
import com.waz.utils.JsonDecoder
import com.waz.utils.crypto.AESUtils
import org.json.JSONObject
import org.scalatest._
import org.threeten.bp.Instant

class EventSpec extends AndroidFreeSpec with GivenWhenThen {
  import EventSpec._
  import MessageEvent._

  feature("Event parsing") {

    scenario("parse UserConnectionEvent") {

      Given("some json data")
      val js = new JSONObject(userConectionEventData)

      When("parsing json")
      val event = EventDecoder(js)

      Then("we should have a UserConnectionEvent")
      event.isInstanceOf[UserConnectionEvent] shouldEqual true
      event.asInstanceOf[UserConnectionEvent].status should be(UserData.ConnectionStatus.PendingFromUser)
      event.asInstanceOf[UserConnectionEvent].convId should be(RConvId("f660330f-f0e3-4511-8d15-71251f44ce32"))
      event.asInstanceOf[UserConnectionEvent].to should be(otherUser.id)
      event.asInstanceOf[UserConnectionEvent].from should be(selfUser.id)
      event.asInstanceOf[UserConnectionEvent].lastUpdated should be(RemoteInstant.ofEpochMilli(JsonDecoder.parseDate("2014-06-12T10:04:02.047Z").getTime))
      event.asInstanceOf[UserConnectionEvent].message should be(Some("Hello Test"))
    }

    scenario("Read receipt off messages are parsed correctly") {
      val readReceiptJson = new JSONObject(
      s"""{
         |  "key": "${PropertyKey.ReadReceiptsEnabled}",
         |  "type": "user.properties-delete",
         |  "value": "0"
         |}""".stripMargin)
      val res = PropertyEvent.Decoder(readReceiptJson)
      res.isInstanceOf[ReadReceiptEnabledPropertyEvent] shouldEqual true
      res.asInstanceOf[ReadReceiptEnabledPropertyEvent].value shouldEqual 0
    }

    scenario("Read receipt on messages are parsed correctly") {
      val readReceiptData = new JSONObject(
        s"""{
           |  "key": "${PropertyKey.ReadReceiptsEnabled}",
           |  "type": "user.properties-set",
           |  "value": "1"
           |}""".stripMargin)
      val res = PropertyEvent.Decoder(readReceiptData)
      res.isInstanceOf[ReadReceiptEnabledPropertyEvent] shouldEqual true
      res.asInstanceOf[ReadReceiptEnabledPropertyEvent].value shouldEqual 1
    }

    scenario("Folders and favorites property event is parsed correctly") {

      // given
      val foldersData = new JSONObject(
        s"""{
           |  "key": "${PropertyKey.Folders}",
           |  "type": "user.properties-set",
           |  "value": {
           |    "labels": [
           |      {
           |        "name": "Project Zeta",
           |        "id": "69ed036b-2d10-4ad2-81fc-4a5b3476205b",
           |        "type": 0,
           |        "conversations": [
           |          "0d61425f-1a4e-4111-9641-4e0da34798cf",
           |          "f4c0208c-f19b-4d9f-8fa0-e45a08b01bf2"
           |        ]
           |      },
           |      {
           |        "id": "1abcd64e-4a7f-48da-8362-28e660e7c553",
           |        "type": 1,
           |        "conversations": [
           |          "0d61425f-1a4e-4111-9641-4e0da34798cf",
           |          "f4c0208c-f19b-4d9f-8fa0-e45a08b01bf2"
           |        ]
           |      }
           |    ]
           |  }
           |}""".stripMargin
      )

      // when
      val res = PropertyEvent.Decoder(foldersData)

      // then
      res.isInstanceOf[FoldersEvent] shouldEqual true
      val folderEvent = res.asInstanceOf[FoldersEvent]
      folderEvent.folders.length shouldEqual 2
      val folder1 = folderEvent.folders(0)
      val folder2 = folderEvent.folders(1)

      folder1.folderData.name shouldBe Name("Project Zeta")
      folder1.folderData.id shouldBe FolderId("69ed036b-2d10-4ad2-81fc-4a5b3476205b")
      folder1.folderData.folderType shouldBe 0
      folder1.conversations shouldBe Set(
        RConvId("0d61425f-1a4e-4111-9641-4e0da34798cf"),
        RConvId("f4c0208c-f19b-4d9f-8fa0-e45a08b01bf2")
      )

      folder2.folderData.name shouldBe Name("")
      folder2.folderData.id shouldBe FolderId("1abcd64e-4a7f-48da-8362-28e660e7c553")
      folder2.folderData.folderType shouldBe 1
      folder2.conversations shouldBe Set(
        RConvId("0d61425f-1a4e-4111-9641-4e0da34798cf"),
        RConvId("f4c0208c-f19b-4d9f-8fa0-e45a08b01bf2")
      )
    }

    scenario("Folders and favorites property deletion event is parsed properly") {

      // given
      var foldersData = new JSONObject(
        s"""{
           |  "key": "${PropertyKey.Folders}",
           |  "type": "user.properties-delete"
           |}""".stripMargin
      )

      // when
      val res = PropertyEvent.Decoder(foldersData)

      // then
      res.isInstanceOf[FoldersEvent] shouldEqual true
      val folderEvent = res.asInstanceOf[FoldersEvent]
      folderEvent.folders.length shouldEqual 0
    }

    scenario("parse otr message event") {
      EventDecoder(new JSONObject(OtrMessageEvent)) match {
        case ev: OtrMessageEvent =>
          ev.convId shouldEqual RConvId("dd2d342a-0756-4710-a033-0544d2752570")
          ev.sender shouldEqual ClientId("44184d922af83522")
          ev.recipient shouldEqual ClientId("b4ca17e659751527")
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("encode/decode GenericMessageEvent") {
      val msg = GenericMessageEvent(RConvId(), None, RemoteInstant(Instant.now()), UserId(), None, GenericMessage.TextMessage("content"))
      EventDecoder(MessageEventEncoder(msg)) match {
        case ev: GenericMessageEvent =>
          ev.convId shouldEqual msg.convId
          ev.content.equals(msg.content)
          ev.from shouldEqual msg.from
          ev.time shouldEqual msg.time
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("encode/decode OtrErrorEvent(duplicate)") {
      val msg = OtrErrorEvent(RConvId(), None, RemoteInstant(Instant.now()), UserId(), None, Duplicate)
      EventDecoder(MessageEventEncoder(msg)) match {
        case ev: OtrErrorEvent =>
          ev.convId shouldEqual msg.convId
          ev.time shouldEqual msg.time
          ev.from shouldEqual msg.from
          ev.error shouldEqual msg.error
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("encode/decode OtrErrorEvent(DecryptionError)") {
      val msg = OtrErrorEvent(RConvId(), None, RemoteInstant(Instant.now()), UserId(), None, DecryptionError("error", Some(100), UserId(), ClientId()))
      EventDecoder(MessageEventEncoder(msg)) match {
        case ev: OtrErrorEvent =>
          ev.convId shouldEqual msg.convId
          ev.time shouldEqual msg.time
          ev.from shouldEqual msg.from
          ev.error shouldEqual msg.error
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("encode/decode OtrErrorEvent(IdentityChanged)") {
      val msg = OtrErrorEvent(RConvId(), None, RemoteInstant(Instant.now()), UserId(), None, IdentityChangedError(UserId(), ClientId()))
      EventDecoder(MessageEventEncoder(msg)) match {
        case ev: OtrErrorEvent =>
          ev.convId shouldEqual msg.convId
          ev.time shouldEqual msg.time
          ev.from shouldEqual msg.from
          ev.error shouldEqual msg.error
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("encode/decode SessionReset") {
      val msg = SessionReset(RConvId(), None, RemoteInstant(Instant.now()), UserId(), None, ClientId())
      EventDecoder(MessageEventEncoder(msg)) match {
        case ev: SessionReset =>
          ev.convId shouldEqual msg.convId
          ev.time shouldEqual msg.time
          ev.from shouldEqual msg.from
          ev.sender shouldEqual msg.sender
        case e => fail(s"unexpected event: $e")
      }
    }


    scenario("Parse MemberUpdateEvent") {
      val rConvId = RConvId("bbe1053c-4999-4324-8a2a-851ce48c56c5")
      val userId = UserId("b937e85e-3611-4e29-9bda-6fe39dfd4bd0")
      val senderId = UserId("bea00721-4af0-4204-82a7-e152c9722ddc")
      val jsonStr =
        s"""
           |{
           |  "conversation": "${rConvId.str}",
           |  "time": "2019-12-11T12:40:38.426Z",
           |  "data": { "conversation_role":"${ConversationRole.AdminRole.label}","target":"${userId.str}"},
           |  "from": "${senderId.str}",
           |  "type":"conversation.member-update"
           |}
         """.stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: MemberUpdateEvent =>
          ev.convId shouldEqual rConvId
          ev.from shouldEqual senderId
          ev.state.target shouldEqual Some(userId)
          ev.state.conversationRole shouldEqual Some(ConversationRole.AdminRole)
        case e => fail(s"unexpected event: $e")
      }

    }

    scenario("Parse MemberLeaveEvent with reason") {
      val rConvId = RConvId("bbe1053c-4999-4324-8a2a-851ce48c56c5")
      val userId1 = UserId("b937e85e-3611-4e29-9bda-6fe39dfd4bd0")
      val userId2 = UserId("b937e85e-3611-4e29-9bda-6fe39dfd4bd1")
      val senderId = UserId("bea00721-4af0-4204-82a7-e152c9722ddc")
      val jsonStr =
        s"""
           |{
           |  "conversation": "${rConvId.str}",
           |  "time": "2019-12-11T12:40:38.426Z",
           |  "data": {
           |    "user_ids": ["${userId1.str}", "${userId2.str}"],
           |    "reason": "legalhold-policy-conflict"
           |  },
           |  "from": "${senderId.str}",
           |  "type": "conversation.member-leave"
           |}
         """.stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: MemberLeaveEvent =>
          ev.convId shouldEqual rConvId
          ev.from shouldEqual senderId
          ev.userIds.toSet shouldEqual Set(userId1, userId2)
          ev.reason shouldEqual Some(MemberLeaveReason.LegalHoldPolicyConflict)
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("Parse MemberLeaveEvent without reason") {
      val rConvId = RConvId("bbe1053c-4999-4324-8a2a-851ce48c56c5")
      val userId1 = UserId("b937e85e-3611-4e29-9bda-6fe39dfd4bd0")
      val userId2 = UserId("b937e85e-3611-4e29-9bda-6fe39dfd4bd1")
      val senderId = UserId("bea00721-4af0-4204-82a7-e152c9722ddc")
      val jsonStr =
        s"""
           |{
           |  "conversation": "${rConvId.str}",
           |  "time": "2019-12-11T12:40:38.426Z",
           |  "data": {
           |    "user_ids": ["${userId1.str}", "${userId2.str}"]
           |  },
           |  "from": "${senderId.str}",
           |  "type": "conversation.member-leave"
           |}
         """.stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: MemberLeaveEvent =>
          ev.convId shouldEqual rConvId
          ev.from shouldEqual senderId
          ev.userIds.toSet shouldEqual Set(userId1, userId2)
          ev.reason shouldEqual None
        case e => fail(s"unexpected event: $e")
      }
    }

    scenario("parse LegalHoldRequestEvent") {
      val jsonStr =
        """
          |{
          |  "client": {
          |    "id": "123"
          |  },
          |  "last_prekey": {
          |    "id": 456,
          |    "key": "oENwaFy74nagzFBlqn9nOQ=="
          |  },
          |  "id": "858db163-c05d-486f-a478-cfe912e9ccde",
          |  "type": "user.legalhold-request"
          |}
          |""".stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: LegalHoldRequestEvent =>
          ev.userId shouldEqual UserId("858db163-c05d-486f-a478-cfe912e9ccde")
          ev.request.clientId.str shouldEqual "123"
          ev.request.lastPreKey.id shouldEqual 456
          ev.request.lastPreKey.data shouldEqual AESUtils.base64("oENwaFy74nagzFBlqn9nOQ==")
        case e =>
          fail(s"unexpected event: $e")
      }
    }

    scenario("parse LegalHoldEnableEvent") {
      val jsonStr =
        """
          |{
          |  "id": "858db163-c05d-486f-a478-cfe912e9ccde",
          |  "type": "user.legalhold-enable"
          |}
          |""".stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: LegalHoldEnableEvent =>
          ev.userId shouldEqual UserId("858db163-c05d-486f-a478-cfe912e9ccde")
        case e =>
          fail(s"unexpected event: $e")
      }
    }

    scenario("parse LegalHoldDisableEvent") {
      val jsonStr =
        """
          |{
          |  "id": "858db163-c05d-486f-a478-cfe912e9ccde",
          |  "type": "user.legalhold-disable"
          |}
          |""".stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: LegalHoldDisableEvent =>
          ev.userId shouldEqual UserId("858db163-c05d-486f-a478-cfe912e9ccde")
        case e =>
          fail(s"unexpected event: $e")
      }
    }

    scenario("parse FeatureConfigUpdateEvent") {
      val jsonStr =
        """
          |{
          |  "type": "feature-config.update",
          |  "name": "fileSharing",
          |  "data": {"status":"enabled"}
          |}
          |""".stripMargin

      val jsonObject = new JSONObject(jsonStr)
      EventDecoder(jsonObject) match {
        case ev: FeatureConfigUpdateEvent =>
          ev.name shouldEqual "fileSharing"
          ev.data shouldEqual "{\"status\":\"enabled\"}"

        case e =>
          fail(s"unexpected event: $e")
      }
    }
  }
}

object EventSpec {
  val selfUser = UserData("Self User")
  val otherUser = UserData("Other User")

  val userConectionEventData =
    s"""{
       |  "connection": {
       |    "status": "sent",
       |    "conversation": "f660330f-f0e3-4511-8d15-71251f44ce32",
       |    "to": "${otherUser.id}",
       |    "from": "${selfUser.id}",
       |    "last_update": "2014-06-12T10:04:02.047Z",
       |    "message": "Hello Test"
       |  },
       |  "type": "user.connection"
       |}""".stripMargin

  val OtrMessageEvent =
    """{
      |  "time":"2015-07-03T15:25:13.527558000000Z",
      |  "data":{
      |     "sender":"44184d922af83522",
      |     "text":"AAAAAQAAAAAAAAAgha0KAUlbvJuueoigdS1cDhZH4AJVp4xmDv7zb2b6BKcAAAAAAAAAzgAAAAIAAQAAAAAAAAAgeqidtPXlu1D5UfhSKlktO9ZQOYe3YQKzywol55dn4KsAAAAAAAAAIDv4DJYmBYe1VHs7ifnMNicAiJrRt3ekeaYBdkVIp5SUAAAAAAAAABC0x6rwKHIsdexmkY\/SUf7dAAAAAAAAAAAAAAAAAAAAIJ7muZOG5JvpmIyitHNsEuhf0GWyHzR+dL1wmXfF8\/cZAAAAAAAAACiwRzc0GxT05zRRrvSdH4LApgzRXzv7eqgvroY+4kFLkTHVuHaDj494",
      |     "recipient":"b4ca17e659751527"
      |  },
      |  "conversation":"dd2d342a-0756-4710-a033-0544d2752570",
      |  "from":"e9e837d6-a12c-492f-9938-7fe61af3971c",
      |  "type":"conversation.otr-message-add"
      |}""".stripMargin

  val OtrAssetEvent =
    """{
      |   "time": "2015-08-19T15:32:28.975Z",
      |   "data": {
      |      "id": "fb325cac-d2d8-4afe-b236-35ac438a9e83",
      |      "sender": "b79a049114ff051e",
      |      "recipient": "650d1a5efc422126",
      |      "key": "gwFYIEqZoz5mJuE/ED5Lu8xKghry2J47ioSImmBN25J2VBm7WJUBUA3t7pjIWElaMd8i/oEKXFEBAVggjYIL1I2gyqBvBGjwK6zOqRTi9KdZ4tPvgQxGon9bdO1YXRgPNx5krQRF5CyJWRukXlZFFA2mdxbf/sUWVUlbcaTOymlNuEaDxFN7K5paQw+9desV39fNx1yDgRLfLLWK9Cet936lZVS2nYVHmncuHRu2t2knlJVkpHe90TCE5A=="
      |   },
      |   "conversation": "74f85659-4677-4b17-91dd-d49cd703b234",
      |   "from": "bf59ae41-3dca-4099-b871-ce5940939166",
      |   "type": "conversation.otr-asset-add"
      |}""".stripMargin

  val OtrAssetEvent1 =
    """{
      |   "time":"2015-08-20T09:35:07.552Z",
      |   "data":{
      |      "id":"2e8c9b0e-9a98-41aa-bf0e-f1395a3b5b39",
      |      "sender":"80b8a91aeb4b4dd",
      |      "data":"z4kzPCP7Lc3MQzap5uqMPdA4UyYsptTem9Rfvalo1WENnq9NScp+EChXRb+bKH9D7oXDXofkTSxwSRmFbzMsOp\/Wo3bGV\/8nUtfXAznRUA6u8L+7IZnqpCk82UkEHPFkI1Hv5AuOgv9UCan1JhS3qL1GKOznUNcJAZUkwBYuhDouM+dGtjyAiY1xcMDEkrHne4gInJP3Ehg+hNx\/HZO6w15L6r4mC2BYy8g+NMdJQAsKdP\/Ye1fxB7hXVoHWZkiI54gStqFAxsUGqJIiznAm3Za6TNjyT\/pLl8d4d5t7c1aE7gDEt8IMQAL3OHlwxv9OkLfs2dPZAjdFZpqJh4INCrqdPBojKxSUwWm9O9A\/9jAt0CRUXeivwy22YmXrgVB0trkgfWBS5Q+QlxQH7ipZ3oGToCl5GdBwnqhq8cOPLdxe9tqdI66UMrEGDvS0bozjo3vUeB8lGUcVCH6HZq\/98FdTSYE5WE57OUJx1yolp75C4\/KijdfHgmZYB9vzDDJoF\/G\/LspKhxbgijnQcB+HpVrk3HTFoYSC7K5231au1dzbTZYb5VPfy1bhAoZCBFoDfwVlFNwdacauhVIJGvGpsyBMBRBMC0aV3kDt7XbyRnRdPW6PT91ML5i0FB051x7yyNvzz6z0qON7M3LcE9OpLrjJVu8Ex30514DQIj7lpCajZlrr5B1t7qhZeqGgwzb5v\/LGxdapzyrTRii0c4U1rZeiYxq70KKxdEJrpRHMYAXexYaOWV48OmCrk4sELmNg5q6gnjZKzDhMpmNGWU5+bLfvgQP1hDQ7g+z29wbPU25F8geD016Yml42o9B5f3WbEjQNib8bpW7zfMR8BtAU04\/zYMAJHV52fc0y3FVY1gE8OqGOYIk8gQ0xu6FT3I5g4nJrTWYRX+JHYjxyb54c5H1m\/bPV9p9+9\/z3iuKaWibop2GbMBI753f6Yl4qm8SRSCFtLOfbhxMOMouiCybtUJEaLS483Ut72ThAM4Yh8pSID9EgGK9bWB2NpweEEDEufe\/vYq91yxnsNNvYdSNo0Q==",
      |      "recipient":"ff23d4857147e00c",
      |      "key":"gwFYIISpSrQ\/SyUBOztOhzqkUtdx\/Hos\/s6PI0RVS6k9bfX+WKQBUDrBCBeeD1\/haWMm\/6Z+LGkBAFggWruzBEiPmq7Slu9uL47OwK6vwfXsoOCKVEXKWHy9Q6hYbF8bO3GM\/B6AGGsNc8qQLWfq8eRyx83FdHL618supmkoKAzav66F9afNgg266080JDP6uWZZlPEgsi9jZOMvpV428mLts\/G7+rhTbBkiNNTbG6x+d0oinSKRl4UyGY2YrN9Locbkz\/9tYPTWjQ=="
      |   },
      |   "conversation":"e74e62ea-1bcd-4582-ab12-bf7a0ff43931",
      |   "from":"dbf13c1b-b7f5-49fd-988b-9eed329d43a8",
      |   "type":"conversation.otr-asset-add"
      |}""".stripMargin
}
