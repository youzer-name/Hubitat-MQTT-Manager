/**
 *  MQTT Manager Topic (driver)
 *
 *  Version 0.2.0
 *  https://github.com/youzer-name/Hubitat-MQTT-Manager (MIT License)
 *
 *  Child device of "MQTT Manager Connection". Each message that arrives on
 *  this device's topic:
 *   - sets the "payload" attribute (usable as a custom attribute trigger)
 *   - turns the switch on or off if the payload is on/off/true/false (any case)
 *   - pushes button 1, if "Push button 1 on every message" is turned on
 *  Sending a notification to this device publishes the text to its topic.
 *  Add, edit and delete these devices with the "MQTT Manager" app.
 *
 *  The "QoS" setting is used to subscribe to the topic and as the default
 *  when sending. "Default retain flag" applies to sending only. A qos or
 *  retain value given to the publish command overrides them.
 */

import groovy.transform.Field

@Field static final List<String> ON_WORDS = ["on", "true"]
@Field static final List<String> OFF_WORDS = ["off", "false"]

metadata {
    definition(
        name: "MQTT Manager Topic",
        namespace: "youzer-name",
        author: "youzer-name",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-MQTT-Manager/main/MqttManagerTopic.groovy"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Notification"
        capability "PushableButton"
        capability "Switch"

        attribute "payload", "STRING"
        attribute "topic", "STRING"

        command "publish", [
            [name: "payload", type: "STRING"],
            [name: "qos", type: "INTEGER", description: "0, 1 or 2 (blank: the QoS setting)"],
            [name: "retain", type: "STRING", description: "true or false (blank: the Default retain flag setting)"]
        ]
    }

    preferences {
        input name: "topicQos", type: "enum", title: "QoS",
              description: "Used to subscribe to this topic, and as the default when sending",
              options: ["0": "0 - at most once", "1": "1 - at least once", "2": "2 - exactly once"],
              defaultValue: "1"
        input name: "defaultRetain", type: "bool", title: "Default retain flag",
              description: "Used when sending. When on, the broker keeps the last message and gives it to new subscribers",
              defaultValue: false
        input name: "everyMessage", type: "bool", title: "Trigger on every message",
              description: "When off, a message with the same payload as the last one doesn't trigger payload rules",
              defaultValue: false
        input name: "buttonEnable", type: "bool", title: "Push button 1 on every message",
              description: "For Button Controller and button triggers", defaultValue: false
    }
}

void installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}

void updated() {
    sendEvent(name: "numberOfButtons", value: 1)
    // The QoS setting may have changed, so the connection resubscribes.
    parent?.topicSettingsChanged()
}

// Called by the connection device for each message on this device's topic.
void parse(String payload) {
    Map evt = [name: "payload", value: payload]
    if (everyMessage) evt.isStateChange = true
    sendEvent(evt)

    String word = payload?.trim()?.toLowerCase()
    if (word in ON_WORDS) sendEvent(name: "switch", value: "on")
    else if (word in OFF_WORDS) sendEvent(name: "switch", value: "off")

    if (buttonEnable) sendEvent(name: "pushed", value: 1, isStateChange: true)
}

// Publishes to this device's topic (not available for wildcard topics).
// A blank qos or retain uses this device's settings.
void publish(String payload, qos = null, retain = null) {
    def q = (qos == null || qos.toString().trim() == "") ? (settings.topicQos ?: "1") : qos
    def r = (retain == null || retain.toString().trim() == "") ? (settings.defaultRetain ? true : false) : retain
    parent?.childPublish(device.deviceNetworkId, payload, q, r)
}

// Notification: sends the text to this device's topic.
void deviceNotification(String text) {
    publish(text)
}

// Switch: publishes "on" or "off". The switch changes when that message
// comes back from the broker, so it always shows what the topic last said.
void on() {
    publish("on")
}

void off() {
    publish("off")
}

// Button: a push from the device page or an app, handy for testing rules.
void push(button = 1) {
    sendEvent(name: "pushed", value: (button ?: 1) as int, isStateChange: true)
}
