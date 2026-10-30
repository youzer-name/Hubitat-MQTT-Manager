/**
 *  MQTT Manager Topic (driver)
 *
 *  Version 0.1.0
 *  https://github.com/youzer-name/Hubitat-MQTT-Manager (MIT License)
 *
 *  Child device of "MQTT Manager Connection". Each message that arrives on
 *  this device's topic sets the "payload" attribute, which rules can use as a
 *  custom attribute trigger. Add, edit and delete these devices with the
 *  "MQTT Manager" app.
 */

metadata {
    definition(
        name: "MQTT Manager Topic",
        namespace: "youzer-name",
        author: "youzer-name",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-MQTT-Manager/main/MqttManagerTopic.groovy"
    ) {
        // Actuator and Initialize match the old "MQTT Handler" devices, so apps
        // that pick devices by those capabilities still list these.
        capability "Actuator"
        capability "Initialize"
        capability "Sensor"

        attribute "payload", "STRING"
        attribute "topic", "STRING"

        command "publish", [
            [name: "payload", type: "STRING"],
            [name: "qos", type: "INTEGER", description: "0, 1 or 2 (default 1)"],
            [name: "retain", type: "STRING", description: "true or false (default false)"]
        ]
    }

    preferences {
        input name: "everyMessage", type: "bool", title: "Trigger on every message",
              description: "When off, a message with the same payload as the last one doesn't trigger rules",
              defaultValue: false
    }
}

void installed() {}

void updated() {}

void initialize() {}

// Publishes to this device's topic (not available for wildcard topics).
void publish(String payload, qos = 1, retain = false) {
    parent?.childPublish(device.deviceNetworkId, payload, qos, retain)
}
