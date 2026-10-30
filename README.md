# MQTT Manager for Hubitat

An app plus two drivers for receiving and sending MQTT messages on Hubitat.
Add, edit and delete topic devices from the app's pages instead of typing
commands on a device page.

Replaces parasaurolophus's
[MQTT Connection / MQTT Handler](https://github.com/parasaurolophus/hubitat-mqtt-connection)
drivers and can take over an existing setup without changing device IDs.

## Parts

| File | Type | What it does |
|---|---|---|
| `MqttManagerApp.groovy` | App | Pages to set up the broker and add, edit or delete topic devices. Shows connection status and each device's last message. |
| `MqttManagerConnection.groovy` | Driver | Holds the broker connection, routes incoming messages to topic devices, and has the `publish(topic, payload, qos, retain)` command for rules. |
| `MqttManagerTopic.groovy` | Driver | One device per topic. Each message sets its `payload` attribute. Also has a `publish` command that sends to its own topic. |

Only drivers can open an MQTT connection on Hubitat, so the connection lives in a
device and the app manages it.

## Features

- Topic filters can use MQTT wildcards: `+` for one level, `#` for the rest.
- Several devices can listen on the same topic.
- Reconnects on its own with backoff (5 s up to 5 min), plus a once-a-minute check.
- Overlapping subscriptions (like `home/+` and `home/door`) are merged, so the
  broker doesn't send the same message twice.
- Per topic device: **Trigger on every message**. When off (the default), a
  message with the same payload as the last one doesn't trigger rules, which
  matches the old driver.
- Connection device: **Log each message received**.

## MQTT Manager or Hubitat's built-in MQTT integrations?

Hubitat 2.5.0 and later include **MQTT Export** (sends hub device events to a
broker) and **MQTT Import Integration** (beta, under **Integrations**). They do
different jobs from MQTT Manager:

| | MQTT Import Integration (built-in) | MQTT Manager |
|---|---|---|
| Best for | Devices that report a state: switches, sensors, locks, thermostats | Signals and triggers: doorbells, camera alerts, Node-RED flags, any text or JSON |
| What it creates | Standard Hubitat devices from a fixed list of capabilities | One device per topic that stores whatever payload arrives |
| Payloads | Each value must map to a capability's attribute (`ON` to `on`, and so on) | Any text, used as-is in rules |
| Setup | Automatic for Home Assistant discovery, Zigbee2MQTT and Tasmota; a mapping wizard for other topics | Add a topic device from the app's pages |
| Wildcards (`+`, `#`) | No, one mapping per topic | Yes |
| Publishing from rules | Only through a mapped device's commands (on, off, setLevel...) | `publish(topic, payload, qos, retain)` for any topic and message |
| Firmware | 2.5.0 or later, beta | Any firmware with MQTT support |

Use the built-in integration when a topic is a device's state and you want a
real switch or sensor that dashboards, voice assistants and other apps
understand. Use MQTT Manager when you want a rule to react to a message, or to
send any message to any topic. The two can run side by side on the same broker.

## Install

1. Add the three files under **Apps Code** and **Drivers Code**.
2. **Apps > Add User App > MQTT Manager**.
3. Choose one:
   - **Create a new connection**: enter the broker URL (`tcp://host:1883` or
     `ssl://host:8883`) and click **Done**. Open the app again to add topic devices.
   - **Use an existing connection device**: pick a device that uses the
     *MQTT Manager Connection* driver.

## Moving from the parasaurolophus drivers

Your connection device and its handler devices keep their IDs, so rules,
dashboards and Maker API keep working.

Hubitat clears a device's stored state when its driver changes, and that's where
the old driver keeps its topic list. The new driver recovers what it can:

1. The `topicList` value in the device's **Data** section, which survives a
   driver change. Pre-loading it before the switch gives a clean move.
2. The latest `topic` event on each handler device, if it's still in the
   device's event history.

Any handler whose topic couldn't be recovered shows as **not set** in the app.
Set its topic from **Edit a topic device**.

Steps:

1. Note each handler's topic and QoS. The old driver shows them under
   **State Variables > handlers** on the connection device's page.
2. On the connection device's page, change **Type** to *MQTT Manager Connection*
   and click **Save Device**. It reconnects within a few seconds on its own.
3. Add the MQTT Manager app, choose **Use an existing connection device**, and
   pick that device.
4. Fix any topic shown as **not set**.

The handler devices keep working on the old *MQTT Handler* driver. To move them
to *MQTT Manager Topic*: Hubitat won't let you change their Type, because the old
driver created them as component devices. Instead, open the *MQTT Handler* driver
under **Drivers Code** and replace its code with `MqttManagerTopic.groovy`. Do
this before installing a separate copy of the topic driver, since two drivers
can't share a name. The handler devices then run the new code with the same IDs.
Both drivers have the Actuator and Initialize capabilities, so apps that pick
devices that way still see them.
