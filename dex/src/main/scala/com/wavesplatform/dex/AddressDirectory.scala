package com.wavesplatform.dex

import akka.actor.{Actor, ActorRef, Props, SupervisorStrategy, Terminated}
import com.wavesplatform.dex.db.OrderDB
import com.wavesplatform.dex.domain.account.Address
import com.wavesplatform.dex.domain.utils.{EitherExt2, ScorexLogging}
import com.wavesplatform.dex.history.HistoryRouter._
import com.wavesplatform.dex.model.Events
import com.wavesplatform.dex.model.Events.OrderCancelFailed

import scala.collection.mutable

class AddressDirectory(orderDB: OrderDB, addressActorProps: (Address, Boolean) => Props, historyRouter: Option[ActorRef])
    extends Actor
    with ScorexLogging {

  import AddressDirectory._
  import context._

  private var startSchedules: Boolean = false
  private[this] val children          = mutable.AnyRefMap.empty[Address, ActorRef]

  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  private def createAddressActor(address: Address): ActorRef = {
    log.debug(s"Creating address actor for $address")
    watch(actorOf(addressActorProps(address, startSchedules), address.toString))
  }

  private def forward(address: Address, msg: Any): Unit = {
    val handler = children.getOrElseUpdate(address, createAddressActor(address))
    handler.forward(msg)
  }

  override def receive: Receive = {
    case Envelope(address, cmd) => forward(address, cmd)

    case e @ Events.OrderAdded(lo, timestamp) =>
      forward(lo.order.sender, e)
      historyRouter foreach { _ ! SaveOrder(lo, timestamp) }

    case e: Events.OrderExecuted =>
      import e.{submitted, counter}
      forward(submitted.order.sender, e)
      if (counter.order.sender != submitted.order.sender) forward(counter.order.sender, e)
      historyRouter foreach { _ ! SaveEvent(e) }

    case e: Events.OrderCanceled =>
      forward(e.acceptedOrder.order.sender, e)
      historyRouter foreach { _ ! SaveEvent(e) }

    case e: OrderCancelFailed =>
      orderDB.get(e.id) match {
        case Some(order) => forward(order.sender.toAddress, e)
        case None        => log.warn(s"The order '${e.id}' not found")
      }

    case StartSchedules =>
      if (!startSchedules) {
        startSchedules = true
        context.children.foreach(_ ! StartSchedules)
      }

    case Terminated(child) =>
      val addressString = child.path.name
      val address       = Address.fromString(addressString).explicitGet()
      children.remove(address)
      log.warn(s"Address handler for $addressString terminated")
  }
}

object AddressDirectory {
  case class Envelope(address: Address, cmd: AddressActor.Message)
  case object StartSchedules
}
