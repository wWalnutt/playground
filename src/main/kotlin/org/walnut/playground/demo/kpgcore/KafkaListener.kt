package org.walnut.playground.demo.kpgcore

import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class KafkaListener {
    @KafkaListener(
        topics = ["\${event.internal.change.event}"],
        groupId = "\${otr.order.ordering.consumer.groups.customer-order}",
        filter = "changeTriggerEventFilter",
    )
    fun listen(
    ) {

    }
}
