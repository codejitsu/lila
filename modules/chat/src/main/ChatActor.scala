package lila.chat

import akka.actor._
import akka.pattern.pipe
import play.api.libs.json.JsObject

import actorApi._
import lila.common.Bus
import lila.common.PimpedJson._
import lila.hub.actorApi.chat._
import lila.pref.{ Pref, PrefApi }
import lila.socket.actorApi.{ SocketEnter, SocketLeave }
import lila.socket.Socket

private[chat] final class ChatActor(
    api: Api,
    namer: Namer,
    bus: Bus,
    relationApi: lila.relation.RelationApi,
    prefApi: PrefApi) extends Actor {

  private val members = collection.mutable.Map[String, ChatMember]()

  private val commander = context.actorOf(Props[Commander])

  override def preStart() {
    bus.subscribe(self, 'chat, 'socketDoor, 'relation)
  }

  override def postStop() {
    bus.unsubscribe(self)
  }

  def receive = {

    case line: Line ⇒ lineMessage(line) foreach { json ⇒
      members.values foreach { m ⇒ if (m wants line) m tell json }
    }

    case Tell(uid, line) ⇒ lineMessage(line) foreach { json ⇒
      members get uid foreach { _ tell json }
    }

    case System(chanTyp, chanId, text) ⇒ Chan(chanTyp, chanId) foreach { chan ⇒
      api.systemWrite(chan, text) pipeTo self
    }

    case SetOpen(member, value) ⇒ prefApi.setChatPref(member.userId, _.copy(on = value))

    case Join(member, chan) ⇒ api.join(member, chan) foreach { head ⇒
      member setHead head
      saveAndReload(member)
    }

    case Show(member, chan, value) ⇒ {
      member.updateHead(_.setActiveChanKey(chan.key, value))
      saveAndReload(member)
    }

    case Query(member, toId) ⇒
      api getUser toId foreach { to ⇒
        relationApi.follows(to.id, member.userId) onSuccess {
          case true ⇒ api.join(member, UserChan(member.userId, toId)) foreach { head ⇒
            member setHead head
            saveAndReload(member)
          }
        }
      }

    case lila.hub.actorApi.relation.Block(u1, u2) ⇒ withMembersOf(u1) { member ⇒
      member block u2
      reload(member)
    }

    case lila.hub.actorApi.relation.UnBlock(u1, u2) ⇒ withMembersOf(u1) { member ⇒
      member unBlock u2
      reload(member)
    }

    case Input(uid, o) ⇒ (o str "t") |@| (o obj "d") |@| (members get uid) apply {
      case (typ, data, member) ⇒ typ match {

        case "chat.register" ⇒ api getUser member.userId foreach { user ⇒
          relationApi blocking user.id zip (api get user) foreach {
            case (blocks, head) ⇒ {
              val pageHead = (data str "pageChan" flatMap Chan.parse).fold(head) {
                case c if c.autoActive ⇒ api.join(user, head, c)
                case c                 ⇒ api.show(user, head, c)
              }
              member setHead pageHead
              member setBlocks blocks
              reload(member)
            }
          }
        }
        case "chat.tell" ⇒ data str "text" foreach { text ⇒
          val chanOption = data str "chan" flatMap Chan.parse
          if (text startsWith "/") commander ! Command(chanOption, member, text drop 1)
          else chanOption foreach { chan ⇒
            api.write(chan.key, member.userId, text) foreach { _ foreach self.! }
          }
        }
      }
    }

    case SocketEnter(uid, member) ⇒ member.userId foreach { userId ⇒
      members += (uid -> new ChatMember(uid, userId, member.troll, member.channel))
    }

    case SocketLeave(uid) ⇒ members -= uid
  }

  private def lineMessage(line: Line) = namer line line map { namedLine ⇒
    Socket.makeMessage("chat.line", namedLine.toJson)
  }

  private def withMembersOf(userId: String)(f: ChatMember ⇒ Unit) {
    members.values foreach { member ⇒
      if (member.userId == userId) f(member)
    }
  }

  private def saveAndReload(member: ChatMember) {
    prefApi.setChatPref(member.userId, member.head.updatePref) >>- reload(member)
  }

  private def reload(m: ChatMember) {
    Thread sleep 500
    api getUser m.userId foreach { user ⇒
      api.populate(m.head, user) foreach { chat ⇒
        m tell Socket.makeMessage("chat.reload", chat.toJson)
      }
    }
  }
}
