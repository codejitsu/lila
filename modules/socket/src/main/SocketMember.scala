package lila.socket

trait SocketMember extends Ordered[SocketMember] {

  val channel: JsChannel
  val userId: Option[String]
  val troll: Boolean

  private val privateLiveGames = collection.mutable.Set[String]()
  private val privateActiveChans = collection.mutable.Set[String]()

  def liveGames: Set[String] = privateLiveGames.toSet
  def activeChans: Set[String] = privateActiveChans.toSet

  def addLiveGames(ids: List[String]) { ids foreach privateLiveGames.+= }

  def setActiveChan(key: String, value: Boolean) {
    if (value) privateActiveChans += key
    else privateActiveChans -= key
  }
  def setActiveChans(keys: Set[String]) {
    privateLiveGames --= privateLiveGames
  }

  def isAuth = userId.isDefined

  def compare(other: SocketMember) = ~userId compare ~other.userId
}

object SocketMember {

  def apply(c: JsChannel): SocketMember = apply(c, none, false)

  def apply(c: JsChannel, uid: Option[String], tr: Boolean): SocketMember = new SocketMember {
    val channel = c
    val userId = uid
    val troll = tr
  }
}
