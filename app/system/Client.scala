package org.jousse.bot
package system

import akka.actor._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, Promise, promise }
import scala.util.{ Try, Success, Failure }

final class Client(
    token: Token,
    driver: Driver,
    initialPromise: Promise[PlayerInput]) extends Actor with LoggingFSM[Client.State, Client.Data] {

  import Client._

  startWith(Waiting, Response(initialPromise))

  driver match {

    case Driver.Http => {
      when(Waiting) {
        case Event(game: Game, Response(promise)) => {
          promise success PlayerInput(game, token)
          if (game.finished) stop()
          else goto(Working) using Nothing
        }
      }
      when(Crashed) {
        case Event(game: Game, _) if game.finished => stop()
        case Event(game: Game, _) => stay
      }
    }
    case Driver.Auto(play) => when(Waiting) {

      case Event(game: Game, _) if game.finished => stop()
      case Event(game: Game, _) => {
        context.system.scheduler.scheduleOnce(botDelay, sender, Round.Play(token, play(game)))
        goto(Working) using Nothing
      }
    }
  }

  when(Working, stateTimeout = aiTimeout) {

    case Event(WorkDone(promise), Nothing) => goto(Waiting) using Response(promise)

    case Event(StateTimeout, _) => {
      context.parent ! Timeout(token)
      goto(Crashed)
    }
  }
}

object Client {

  import play.api.Play.current
  private val botDelay = play.api.Play.configuration
    .getMilliseconds("vindinium.auto-client-delay")
    .getOrElse(0l)
    .milliseconds
  private val aiTimeout = play.api.Play.configuration
    .getMilliseconds("vindinium.ai-timeout")
    .getOrElse(0l)
    .milliseconds

  case class WorkDone(promise: Promise[PlayerInput])
  case class Timeout(token: Token)

  sealed trait State
  case object Waiting extends State
  case object Working extends State
  case object Crashed extends State

  sealed trait Data
  case object Nothing extends Data
  case class Response(promise: Promise[PlayerInput]) extends Data
}
