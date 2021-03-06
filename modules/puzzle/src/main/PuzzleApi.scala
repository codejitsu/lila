package lila.puzzle

import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }

import org.joda.time.DateTime
import play.api.libs.iteratee._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import reactivemongo.bson.{ BSONDocument, BSONInteger }

import lila.db.Types.Coll
import lila.user.{ User, UserRepo }

private[puzzle] final class PuzzleApi(
    puzzleColl: Coll,
    attemptColl: Coll,
    apiToken: String) {

  import Puzzle.puzzleBSONHandler

  object puzzle {

    def find(id: PuzzleId): Fu[Option[Puzzle]] =
      puzzleColl.find(BSONDocument("_id" -> id))
        .projection(Puzzle.withoutUsers)
        .one[Puzzle]

    def latest(nb: Int): Fu[List[Puzzle]] =
      puzzleColl.find(BSONDocument())
        .projection(Puzzle.withoutUsers)
        .sort(BSONDocument("date" -> -1))
        .cursor[Puzzle]
        .collect[List](nb)

    def importBatch(json: JsValue, token: String): Funit =
      if (token != apiToken) fufail("Invalid API token")
      else {
        import Generated.generatedJSONRead
        Future(json.as[List[Generated]]) flatMap {
          _.map(_.toPuzzle).sequence.future flatMap insertPuzzles
        }
      }

    def insertPuzzles(puzzles: List[PuzzleId ⇒ Puzzle]): Funit = puzzles match {
      case Nil ⇒ funit
      case puzzle :: rest ⇒ findNextId flatMap { id ⇒
        (puzzleColl insert puzzle(id)) >> insertPuzzles(rest)
      }
    }

    private def findNextId: Fu[PuzzleId] =
      puzzleColl.find(BSONDocument(), BSONDocument("_id" -> true))
        .sort(BSONDocument("_id" -> -1))
        .one[BSONDocument] map {
          _ flatMap { doc ⇒ doc.getAs[Int]("_id") map (1+) } getOrElse 1
        }
  }

  object attempt {

    def find(puzzleId: PuzzleId, userId: String): Fu[Option[Attempt]] =
      attemptColl.find(BSONDocument(
        Attempt.BSONFields.id -> Attempt.makeId(puzzleId, userId)
      )).one[Attempt]

    def vote(a1: Attempt, v: Boolean): Fu[(Puzzle, Attempt)] = puzzle find a1.puzzleId flatMap {
      case None ⇒ fufail(s"Can't vote for non existing puzzle ${a1.puzzleId}")
      case Some(p1) ⇒
        val p2 = a1.vote match {
          case Some(from) ⇒ p1 withVote (_.change(from, v))
          case None       ⇒ p1 withVote (_ add v)
        }
        val a2 = a1.copy(vote = v.some)
        attemptColl.update(
          BSONDocument("_id" -> a2.id),
          BSONDocument("$set" -> BSONDocument(Attempt.BSONFields.vote -> v))) zip
          puzzleColl.update(
            BSONDocument("_id" -> p2.id),
            BSONDocument("$set" -> BSONDocument(Puzzle.BSONFields.vote -> p2.vote))) map {
              case _ ⇒ p2 -> a2
            }
    }

    def add(a: Attempt) = attemptColl insert a void

    def times(puzzleId: PuzzleId): Fu[List[Int]] = attemptColl.find(
      BSONDocument(Attempt.BSONFields.puzzleId -> puzzleId),
      BSONDocument(Attempt.BSONFields.time -> true)
    ).cursor[BSONDocument].collect[List]() map2 {
        (obj: BSONDocument) ⇒ obj.getAs[Int](Attempt.BSONFields.time)
      } map (_.flatten)

    def hasVoted(user: User): Fu[Boolean] = attemptColl.find(
      BSONDocument(Attempt.BSONFields.userId -> user.id),
      BSONDocument(
        Attempt.BSONFields.vote -> true,
        Attempt.BSONFields.id -> false
      )).sort(BSONDocument(Attempt.BSONFields.date -> -1))
      .cursor[BSONDocument]
      .collect[List](5) map {
        _.foldLeft(false) {
          case (true, _)    ⇒ true
          case (false, doc) ⇒ doc.getAs[Boolean](Attempt.BSONFields.vote).isDefined
        }
      }
  }
}
