/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.saga

import com.google.common.annotations.Beta
import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.clouddriver.event.EventListener
import com.netflix.spinnaker.clouddriver.event.SpinEvent
import com.netflix.spinnaker.clouddriver.event.persistence.EventRepository
import com.netflix.spinnaker.clouddriver.saga.SagaService.EventApplyState.APPLIED
import com.netflix.spinnaker.clouddriver.saga.SagaService.EventApplyState.NO_HANDLER
import com.netflix.spinnaker.clouddriver.saga.SagaService.EventApplyState.OUT_OF_ORDER
import com.netflix.spinnaker.clouddriver.saga.exceptions.InvalidSagaCompletionHandlerException
import com.netflix.spinnaker.clouddriver.saga.exceptions.SagaSystemException
import com.netflix.spinnaker.clouddriver.saga.models.Saga
import com.netflix.spinnaker.clouddriver.saga.persistence.SagaRepository
import com.netflix.spinnaker.kork.exceptions.SpinnakerException
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.beans.factory.BeanNotOfRequiredTypeException
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware

/**
 * The brains for the Saga framework, it's entire purpose is to apply a single event and handle the overall business
 * rules of how Saga events should be handled. By staying entirely out of the workflow aspects, a Saga can lay the
 * tracks out in front of itself at runtime.
 *
 * For every [SagaEvent], there is 0 to N [SagaEventHandler]. A [SagaEventHandler] can require 1 to N prerequisite
 * [SagaEvent]s before being invoked, similarly each [SagaEventHandler] can emit 0 to N events, allowing complex
 * fork & join operations.
 *
 * A Saga must have all of its required events present in the event log before it is considered completed and
 * similarly, must have all of its compensation events present in the event log before it is considered rolled back.
 *
 * When creating a new saga, it should first be saved, then applied. Saga changes will be saved automatically
 * by the [SagaService] once it has been initially created.
 *
 * ```
 * val saga = Saga(
 *   name = "aws://v1.Deploy",
 *   id = "my-correlation-id",
 *   completionHandler = "myCompletionHandlerBeanName",
 *   requiredEvents = listOf(
 *     Front50AppLoaded::javaClass.simpleName,
 *     AmazonDeployCreated::javaClass.simpleName,
 *     // ...
 *   ),
 *   compensationEvents = listOf(
 *     // ...
 *   )
 * )
 *
 * sagaService.save(saga)
 * sagaService.apply(saga.name, saga.id, AwsDeployCreated(description, priorOutputs)
 * ```
 *
 * TODO(rz): Metrics
 */
@Beta
class SagaService(
  private val sagaRepository: SagaRepository,
  private val eventRepository: EventRepository,
  private val eventHandlerProvider: SagaEventHandlerProvider,
  private val registry: Registry
) : ApplicationContextAware, EventListener {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val sagaEventFactory = SagaEventFactory(eventRepository)

  private lateinit var applicationContext: ApplicationContext

  private val eventsId = registry.createId("saga.events")
  private val appliedEventsId = registry.createId("saga.events.applied")
  private val failedEventsId = registry.createId("saga.events.failed")

  override fun onEvent(event: SpinEvent) {
    // TODO(rz): `apply` would only occur via Commands; this method could disappear
    if (event is SagaEvent) {
      when (event) {
        is SagaLogAppended,
        is SagaRequiredEventsAdded,
        is SagaRequiredEventsRemoved,
        is SagaInCompensation,
        is SagaCompensated,
        is SagaCompleted,
        is SagaSaved -> {
          // Ignoring this internal noise; these events are basically just for tracing
          log.trace("Ignoring internal event: $event")
          return
        }
        else -> apply(event)
      }
    }
  }

  override fun setApplicationContext(applicationContext: ApplicationContext) {
    this.applicationContext = applicationContext
  }

  /**
   * TODO(rz): Add compensation handling
   * TODO(rz): Wrap whole apply in try/catch; if exception bubbles, probably set saga to complete and fail
   */
  fun apply(event: SagaEvent) {
    requireSynthesizedEventMetadata(event)

    val sagaName = event.sagaName
    val sagaId = event.sagaId
    log.debug("Applying $sagaName/$sagaId: $event")

    val saga = get(sagaName, sagaId)

    if (isEventOutOfOrder(saga, event)) {
      log.warn("Received out-of-order event for saga '${saga.name}/${saga.id}', ignoring: ${event.javaClass.simpleName}")
      registry.counter(eventsId.withTags(STATE_LABEL, OUT_OF_ORDER.name, TYPE_LABEL, event.javaClass.simpleName)).increment()
      return
    }

    val handlers = eventHandlerProvider.getMatching(saga, event)
    val emittedEvents: List<SagaEvent> = if (handlers.isEmpty()) {
      log.debug("No EventHandlers found for event: ${event.javaClass.simpleName}")
      registry.counter(eventsId.withTags(STATE_LABEL, NO_HANDLER.name, TYPE_LABEL, event.javaClass.simpleName)).increment()
      listOf()
    } else {
      handlers
        .flatMap { handler ->
          val handlerEvent = sagaEventFactory.buildCompositeEventForHandler(saga, handler, event)
          log.debug("Applying handler '${handler.javaClass.simpleName}' on ${event.javaClass.simpleName}: " +
            "${saga.name}/${saga.id}")
          try {
            handler.apply(handlerEvent, saga)
          } catch (e: Exception) {
            log.error("Failed applying event ${event.javaClass.simpleName} by handler '${handler.javaClass.simpleName}'", e)

            if (e is SpinnakerException && e.retryable == true) {
              saga.addEvent(SagaEventHandlerErrorOccurred(
                saga.name,
                saga.id,
                handler.javaClass.simpleName,
                e,
                true
              ))
              return@flatMap listOf<SagaEvent>()
            } else {
              throw e
            }
          }
        }
    }
    registry.counter(eventsId.withTags(STATE_LABEL, APPLIED.name, TYPE_LABEL, event.javaClass.simpleName)).increment()

    saga.setSequence(event.metadata.sequence)

    if (allRequiredEventsApplied(saga, event)) {
      saga.complete()
    }

    sagaRepository.save(saga, emittedEvents)
  }

  /**
   * An out-of-order event is typically not an indication of an actual bug, but that another event that was
   * dispatched from the same originating version was applied first.
   *
   * TODO(rz): This logic _probably_ needs to be re-evaluated. Maybe re-drive the event instead with the latest state?
   * Perhaps instead we just don't update the sequence? Not really sure...
   */
  private fun isEventOutOfOrder(saga: Saga, event: SagaEvent): Boolean = false
//    saga.getSequence() + 1 != event.metadata.sequence

  /**
   * Checks the history of applied events (including the [applyingEvent]) to see if all required events have been
   * applied. If true, the Saga will be marked as completed, however the SagaService will continue to process
   * events for it if there are still unapplied events left to process.
   */
  private fun allRequiredEventsApplied(saga: Saga, applyingEvent: SagaEvent): Boolean {
    val allEvents = eventRepository.list(saga.name, saga.id).plus(applyingEvent)

    val appliedRequiredEvents = allEvents.filter { saga.getRequiredEvents().contains(it.javaClass.simpleName) }
    if (!appliedRequiredEvents.map { it.javaClass.simpleName }.containsAll(saga.getRequiredEvents())) {
      return false
    }
    val maxRequiredEventVersion = appliedRequiredEvents
      .map { it.metadata.sequence }
      .max()

    val allApplied = maxRequiredEventVersion != null && maxRequiredEventVersion <= saga.getSequence()
    if (!allApplied) {
      return false
    }

    val maxEventVersion = allEvents.map { it.metadata.sequence }.max()
    if (maxEventVersion != null && maxEventVersion > saga.getSequence()) {
      log.debug("All required Saga events have been applied, however additional events have not yet been applied," +
        " delaying completion: ${saga.name}/${saga.id}")
      return false
    }

    log.info("All required events have occurred and no other events need to be applied, " +
      "completing: ${saga.name}/${saga.id}")
    return true
  }

  /**
   * Event metadata is synthesized lazily; if it hasn't, then there's a bug in the Saga framework.
   */
  private fun requireSynthesizedEventMetadata(event: SagaEvent) {
    try {
      event.metadata.originatingVersion
    } catch (e: UninitializedPropertyAccessException) {
      // If this is thrown, it's a core bug in the library.
      throw SagaSystemException("Event metadata has not been synthesized yet", e)
    }
  }

  /**
   * Get a Saga. An exception will be thrown if the Saga cannot be found.
   */
  fun get(sagaName: String, sagaId: String): Saga {
    return sagaRepository.get(sagaName, sagaId) ?: throw SagaSystemException("Saga must be saved before applying")
  }

  fun save(saga: Saga, onlyIfMissing: Boolean = false) {
    if (onlyIfMissing && sagaRepository.get(saga.name, saga.id) != null) {
      return
    }

    // Just asserting that the completion handler actually exists
    getCompletionHandler(saga)

    sagaRepository.save(saga)
  }

  fun <T> awaitCompletion(saga: Saga): T? {
    // TODO(rz): This obviously doesn't "await" anything; it's a placeholder for when the event publisher is async.
    return get(saga.name, saga.id).let { completedSaga ->
      @Suppress("UNCHECKED_CAST")
      getCompletionHandler(completedSaga)?.apply(completedSaga) as T
    }
  }

  fun <T> awaitCompletion(saga: Saga, callback: (Saga) -> T?): T? {
    return get(saga.name, saga.id).let(callback)
  }

  private fun getCompletionHandler(saga: Saga): SagaCompletionHandler<*>? =
    saga.completionHandler
      ?.let { completionHandler ->
        try {
          applicationContext.getBean(completionHandler, SagaCompletionHandler::class.java)
        } catch (e: NoSuchBeanDefinitionException) {
          throw InvalidSagaCompletionHandlerException.notFound(saga.completionHandler, saga.name, e)
        } catch (e: BeanNotOfRequiredTypeException) {
          throw InvalidSagaCompletionHandlerException.invalidType(
            saga.completionHandler,
            saga.name,
            applicationContext.getType(saga.completionHandler)?.simpleName ?: "unknown",
            e
          )
        } catch (e: BeansException) {
          throw InvalidSagaCompletionHandlerException("Could not load saga completion handler", e)
        }
      }

  private enum class EventApplyState {
    OUT_OF_ORDER,
    APPLIED,
    FAILED,
    NO_HANDLER
  }

  companion object {
    private const val STATE_LABEL = "state"
    private const val TYPE_LABEL = "type"
  }
}