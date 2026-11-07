# MQTT Manager for Hubitat

An app plus two drivers for receiving and sending MQTT messages on Hubitat. Creates a connection device and one child device per subscribed topic. Provides an app page to easily manage MQTT broker settings and add/edit/delete topic devices.  The app displays the connection status and shows the last message received by each topic device.  QOS and retain flag are settable for each topic device.

## Parts

| File | Type | What it does |
|---|---|---|
| `MqttManagerApp.groovy` | App | Pages to set up the broker and add, edit or delete topic devices. Shows connection status and each device's last message. |
| `MqttManagerConnection.groovy` | Driver | Holds the broker connection, routes incoming messages to topic devices, and has the `publish(topic, payload, qos, retain)` command for rules. |
| `MqttManagerTopic.groovy` | Driver | One device per topic. Each message sets its `payload` attribute. Also a switch, a button and a notification device (see below), plus a `publish` command that sends to its own topic. |

## Features

- Topic filters can use MQTT wildcards: `+` for one level, `#` for the rest.
- Several devices can listen on the same topic.
- Reconnects on its own with backoff (5 s up to 5 min), plus a once-a-minute check.
- Overlapping subscriptions (like `home/+` and `home/door`) are merged, so the
  broker doesn't send the same message twice.
- Logging: Option to log each message received by the parent connection device.

## Topic devices

Each topic device works with standard Hubitat apps in these ways:

| Capability | Receiving | Sending |
|---|---|---|
| `payload` attribute | Every message sets it. Use it as a custom attribute trigger in rules, or show it on a dashboard. | `publish(payload, qos, retain)` sends to the device's topic. Blank qos or retain use the device's settings. |
| Switch | `on`, `off`, `true` or `false` (any case, spaces ignored) turn the switch on or off. Other payloads leave it alone. | `on` / `off` publish `on` / `off` to the topic. The switch changes when the broker sends that message back. |
| Button | With **Push button 1 on every message** turned on, every message pushes button 1, even a repeat. For Button Controller and button triggers. | `push` fires the button on the hub only, for testing. |
| Notification | | Any app that sends notifications (Rule Machine, HSM, Notifier...) can pick the device, and the text is published to its topic. |

Settings on each topic device:

- **QoS** (default 1): used to subscribe to the topic, and as the default when
  sending (notifications, `on` / `off`, and `publish` with QoS left blank).
  The app's Add and Edit pages set the same value.
- **Default retain flag** (default off): used when sending. A `publish` with
  a retain value given overrides it.
- **Trigger on every message**: when off (the default), a message with the
  same payload as the last one doesn't trigger `payload` rules.
- **Push button 1 on every message**: off by default.

Publishing doesn't work on wildcard topics. The device also gets its own
messages back from the broker (MQTT 3.1.1 has no "no local" option), so
a rule that publishes to a topic when that same topic changes will loop.

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
| Firmware | 2.5.0 or later, beta | 2.5.2 or later (the version it's tested on) |

Use the built-in integration when a topic is a device's state and you want a
real switch or sensor that dashboards, voice assistants and other apps
understand. Use MQTT Manager when you want a rule to react to a message, or to
send any message to any topic. The two can run side by side on the same broker.

## Install

You need an MQTT broker on your network, such as Mosquitto.

### With Hubitat Package Manager (recommended)

1. In **Hubitat Package Manager**, choose **Install**, then **Search by Keywords**,
   and search for `MQTT Manager`.
2. Install the package. HPM adds the app and both drivers, and keeps them updated.
3. Follow the setup steps below.

### Manually

Add `MqttManagerApp.groovy` under **Apps Code**, and `MqttManagerConnection.groovy`
and `MqttManagerTopic.groovy` under **Drivers Code**. To update later, open each
one and use **Import**.

### Setup

1. **Apps > Add User App > MQTT Manager**.
2. Enter the broker URL (`tcp://host:1883` or `ssl://host:8883`) and, if your
   broker needs them, a username and password. Click **Done** to create the
   connection device.
3. Open the app again to add topic devices.

Removing the app also removes its connection device and topic devices.

## Screenshots

### App Page ###

<img width="1727" height="889" alt="MQTT manager screenshot 1" src="https://github.com/user-attachments/assets/40400127-f52f-4bb4-9c74-082dcbd542d8" />

### Device Preferences Page ###

<img width="1788" height="582" alt="device preferences" src="https://github.com/user-attachments/assets/61d0316d-f2f6-446a-8fc4-807365e7190a" />

