/**
 *  MQTT Manager Connection (driver)
 *
 *  Version 0.1.0
 *  https://github.com/youzer-name/Hubitat-MQTT-Manager (MIT License)
 *
 *  Holds the connection to an MQTT broker and passes incoming messages to its
 *  MQTT Manager Topic child devices. Normally set up and managed by the
 *  "MQTT Manager" app, but it also works on its own.
 *
 *  Drop-in replacement for parasaurolophus's "MQTT Connection" driver:
 *   - Same setting names, same "connection" attribute and same
 *     publish(topic, payload, qos, retain) command, so rules keep working.
 *   - Picks up the old driver's topics after a driver change (see loadTopics).
 *   - Its old child devices ("MQTT Handler") keep working as they are.
 *
 *  Improvements over the old driver:
 *   - Reconnects on a timer with backoff instead of blocking a hub thread.
 *   - Topic filters can use MQTT wildcards (+ and #).
 *   - Several devices can listen on the same topic.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String NAMESPACE = "youzer-name"
@Field static final String TOPIC_DRIVER = "MQTT Manager Topic"
// Seconds to wait before each reconnect attempt; the last value repeats.
@Field static final List<Integer> RETRY_WAITS = [5, 10, 20, 40, 60, 120, 300]
// Recently routed messages, to drop the extra copy the broker sends when a
// topic matches two of our subscriptions. Key: "deviceId|topic|payload".
@Field static final Map<String, Long> RECENT = new ConcurrentHashMap()
@Field static final long DUP_WINDOW_MS = 1000

metadata {
    definition(
        name: "MQTT Manager Connection",
        namespace: "youzer-name",
        author: "youzer-name",
        importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-MQTT-Manager/main/MqttManagerConnection.groovy"
    ) {
        capability "Initialize"
        capability "Actuator"

        attribute "connection", "STRING"

        command "connect"
        command "disconnect"
        command "publish", [
            [name: "topic", type: "STRING"],
            [name: "payload", type: "STRING"],
            [name: "qos", type: "INTEGER"],
            [name: "retain", type: "STRING", description: "true or false"]
        ]
        // Used by the MQTT Manager app. They also work from this page.
        command "addTopic", [
            [name: "name", type: "STRING"],
            [name: "topic", type: "STRING", description: "Topic filter; + and # wildcards allowed"],
            [name: "qos", type: "INTEGER", description: "0, 1 or 2"]
        ]
        command "updateTopic", [
            [name: "device network id", type: "STRING"],
            [name: "name", type: "STRING"],
            [name: "topic", type: "STRING"],
            [name: "qos", type: "INTEGER"]
        ]
        command "removeTopic", [[name: "device network id", type: "STRING"]]
        // Refreshes the topicList device data the app reads. (Apps can only
        // call declared commands on devices they don't own.)
        command "listTopics"
    }

    preferences {
        input name: "broker", type: "text", title: "Broker URL",
              description: "tcp://host:1883 or ssl://host:8883", required: true
        input name: "clientId", type: "text", title: "Client ID",
              description: "Must be unique on the broker", required: true,
              defaultValue: UUID.randomUUID().toString()
        input name: "username", type: "text", title: "Username (optional)", required: false
        input name: "password", type: "password", title: "Password (optional)", required: false
        input name: "lwtTopic", type: "text", title: "Last-will topic",
              description: "Broker publishes this if the hub drops off", defaultValue: "hubitat/lwt"
        input name: "lwtMessage", type: "text", title: "Last-will message", defaultValue: "disconnected"
        input name: "txtEnable", type: "bool", title: "Log each message received", defaultValue: false
        input name: "logEnable", type: "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Lifecycle

void installed() {
    sendEvent(name: "connection", value: "disconnected")
    if (settings.broker?.trim()) initialize()
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    loadTopics()
    unschedule("initialize")  // already starting up; skip loadTopics' delayed start
    state.retryIndex = 0
    unschedule("connect")
    closeConnection()
    connect()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device} debug logging turned off"
}

// ---------------------------------------------------------------------------
// Connection

void connect() {
    loadTopics()
    if (!settings.broker?.trim()) {
        log.warn "${device}: no broker URL set"
        return
    }
    state.wantConnected = true
    // Check the connection every minute in case it dropped without telling us.
    runEvery1Minute("watchdog")
    if (interfaces.mqtt.isConnected()) {
        setConnected(true)
        return
    }
    try {
        logDebug "connecting to ${settings.broker}"
        interfaces.mqtt.connect(
            settings.broker.trim(),
            settings.clientId ?: device.deviceNetworkId,
            settings.username ?: null,
            settings.password ?: null,
            lastWillTopic: settings.lwtTopic ?: "hubitat/lwt",
            lastWillQos: 0,
            lastWillMessage: settings.lwtMessage ?: "disconnected")
        state.retryIndex = 0
        setConnected(true)
        log.info "${device}: connected to ${settings.broker}"
        state.subscribed = [:]
        syncSubscriptions()
    } catch (e) {
        log.error "${device}: could not connect to ${settings.broker}: ${e.message}"
        setConnected(false)
        scheduleReconnect()
    }
}

void disconnect() {
    state.wantConnected = false
    unschedule("connect")
    unschedule("watchdog")
    closeConnection()
    log.info "${device}: disconnected"
}

private void closeConnection() {
    try {
        if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect()
    } catch (e) {
        logDebug "disconnect error: ${e.message}"
    }
    setConnected(false)
    state.subscribed = [:]
}

private void scheduleReconnect() {
    if (!state.wantConnected) return
    int i = (state.retryIndex ?: 0) as int
    int wait = RETRY_WAITS[Math.min(i, RETRY_WAITS.size() - 1)]
    state.retryIndex = i + 1
    log.info "${device}: retrying in ${wait} s"
    runIn(wait, "connect")
}

void watchdog() {
    if (!state.wantConnected) return
    if (interfaces.mqtt.isConnected()) {
        // Keep the attribute in line with the real connection.
        if (device.currentValue("connection", true) != "connected") setConnected(true)
        return
    }
    if (device.currentValue("connection", true) == "connected") {
        log.warn "${device}: connection lost"
        setConnected(false)
    }
    // Only start a retry if one isn't already counting down.
    if (!state.retryIndex) connect()
}

// Always sent: Hubitat skips events whose value hasn't changed. (Checking
// currentValue first can read a stale cached value and skip a real change.)
private void setConnected(boolean up) {
    sendEvent(name: "connection", value: up ? "connected" : "disconnected")
}

// Called by the hub when the connection status changes.
void mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
        log.warn "${device}: ${message}"
        setConnected(false)
        scheduleReconnect()
    } else {
        logDebug "status: ${message}"
    }
}

// ---------------------------------------------------------------------------
// Messages

void parse(String raw) {
    // Messages can arrive right after a driver switch, before anything else runs.
    loadTopics()
    Map msg = interfaces.mqtt.parseMessage(raw)
    String topic = msg.topic
    String payload = msg.payload
    logDebug "received ${topic}: ${payload}"
    if (isDuplicate(topic, payload)) {
        logDebug "dropped duplicate copy of ${topic}"
        return
    }
    Map topics = state.topics ?: [:]
    topics.each { String dni, Map t ->
        if (topicMatches(t.topic as String, topic)) {
            def child = getChildDevice(dni)
            if (child) {
                Map evt = [name: "payload", value: payload]
                if (child.getSetting("everyMessage")) evt.isStateChange = true
                child.sendEvent(evt)
                if (txtEnable) log.info "${child}: ${payload}"
            }
        }
    }
}

// True for the extra copy the broker sends when a topic matches two of our
// subscriptions that partly overlap (like a/+/c and a/b/+).
private boolean isDuplicate(String topic, String payload) {
    int matches = ((state.subscribed ?: [:]).keySet().count { topicMatches(it as String, topic) }) as int
    if (matches < 2) return false
    long now = now()
    String key = "${device.id}|${topic}|${payload}"
    Long last = RECENT.get(key)
    RECENT.put(key, now)
    if (RECENT.size() > 200) RECENT.entrySet().removeIf { now - it.value > DUP_WINDOW_MS }
    return last != null && now - last < DUP_WINDOW_MS
}

// Does filter a match every topic that filter b matches?
static boolean filterCovers(String a, String b) {
    String[] fa = a.split("/", -1)
    String[] fb = b.split("/", -1)
    for (int i = 0; i < fa.length; i++) {
        if (fa[i] == "#") return true
        if (i >= fb.length) return false
        if (fa[i] == "+") { if (fb[i] == "#") return false }
        else if (fa[i] != fb[i]) return false
    }
    return fa.length == fb.length
}

void publish(String topic, String payload, qos = 1, retain = false) {
    if (!topic) {
        log.warn "${device}: publish needs a topic"
        return
    }
    int q = toQos(qos)
    boolean r = retain instanceof Boolean ? retain : retain?.toString()?.trim()?.toBoolean()
    try {
        interfaces.mqtt.publish(topic, payload ?: "", q, r)
        logDebug "published ${topic}: ${payload} (qos ${q}, retain ${r})"
    } catch (e) {
        log.error "${device}: could not publish to ${topic}: ${e.message}"
    }
}

// MQTT topic filter match: + is one level, # is everything after.
static boolean topicMatches(String filter, String topic) {
    if (filter == null || topic == null) return false
    if (filter == topic) return true
    String[] f = filter.split("/", -1)
    String[] t = topic.split("/", -1)
    for (int i = 0; i < f.length; i++) {
        if (f[i] == "#") return true
        if (i >= t.length) return false
        if (f[i] != "+" && f[i] != t[i]) return false
    }
    return f.length == t.length
}

// ---------------------------------------------------------------------------
// Topic devices

// Returns the new device's network id, or null if it couldn't be added.
String addTopic(String name, String topic, qos = 1) {
    loadTopics()
    String err = checkTopic(topic) ?: (name?.trim() ? null : "a name is needed")
    if (err) {
        log.warn "${device}: can't add topic device: ${err}"
        return null
    }
    String dni = UUID.randomUUID().toString()
    try {
        def child = addChildDevice(NAMESPACE, TOPIC_DRIVER, dni,
                                   [name: name.trim(), label: name.trim(), isComponent: true])
        child.sendEvent(name: "topic", value: topic.trim())
    } catch (e) {
        log.error "${device}: could not create topic device: ${e.message}"
        return null
    }
    Map topics = state.topics ?: [:]
    topics[dni] = [topic: topic.trim(), qos: toQos(qos)]
    state.topics = topics
    syncSubscriptions()
    log.info "${device}: added '${name}' on ${topic}"
    listTopics()
    return dni
}

// Returns true on success.
boolean updateTopic(String dni, String name, String topic, qos = 1) {
    loadTopics()
    def child = getChildDevice(dni)
    if (!child) {
        log.warn "${device}: no topic device ${dni}"
        return false
    }
    String err = checkTopic(topic)
    if (err) {
        log.warn "${device}: can't update ${child}: ${err}"
        return false
    }
    Map topics = state.topics ?: [:]
    topics[dni] = [topic: topic.trim(), qos: toQos(qos)]
    state.topics = topics
    if (name?.trim() && name.trim() != child.displayName) child.setLabel(name.trim())
    syncSubscriptions()
    child.sendEvent(name: "topic", value: topic.trim())
    log.info "${device}: updated '${child}' to ${topic} (qos ${toQos(qos)})"
    listTopics()
    return true
}

boolean removeTopic(String dni) {
    loadTopics()
    Map topics = state.topics ?: [:]
    topics.remove(dni)
    state.topics = topics
    syncSubscriptions()
    def child = getChildDevice(dni)
    if (child) {
        String label = child.displayName
        try {
            deleteChildDevice(dni)
            log.info "${device}: removed '${label}'"
        } catch (e) {
            log.error "${device}: could not delete '${label}': ${e.message}"
            listTopics()
            return false
        }
    }
    listTopics()
    return true
}

// Saves one row per topic device as JSON in the device data "topicList", for
// the app to read. (When an app calls a command on a device it doesn't own,
// it doesn't get the return value, so the list is passed through device data.)
String listTopics() {
    loadTopics()
    Map topics = state.topics ?: [:]
    List rows = getChildDevices().collect { child ->
        Map t = topics[child.deviceNetworkId] ?: [:]
        def last = child.currentState("payload")
        [dni         : child.deviceNetworkId,
         id          : child.id,
         name        : child.displayName,
         topic       : t.topic,
         qos         : t.qos,
         payload     : last?.value,
         lastReceived: last?.date?.time]
    }.sort { it.name?.toLowerCase() }
    String json = JsonOutput.toJson(rows)
    device.updateDataValue("topicList", json)
    return json
}

void childPublish(String dni, String payload, qos, retain) {
    String topic = state.topics?.get(dni)?.topic
    if (!topic) return
    if (topic.contains("+") || topic.contains("#")) {
        log.warn "${device}: can't publish to wildcard topic ${topic}"
        return
    }
    publish(topic, payload, qos, retain)
}

// Subscribes to what the topic devices need and drops what they don't.
// A filter covered by a wider one (home/a under home/+) isn't subscribed
// separately, so the broker doesn't send the same message twice.
private void syncSubscriptions() {
    if (!interfaces.mqtt.isConnected()) return
    Map<String, Integer> need = [:]
    (state.topics ?: [:]).values().each { t ->
        if (t.topic) need[t.topic as String] = Math.max(need[t.topic as String] ?: 0, toQos(t.qos))
    }
    Map<String, Integer> want = [:]
    need.each { f, q ->
        if (!need.keySet().any { it != f && filterCovers(it, f) }) want[f] = q
    }
    // A wider filter takes the highest QoS of the filters it covers.
    need.each { f, q -> want.keySet().findAll { it != f && filterCovers(it, f) }.each { w -> want[w] = Math.max(want[w], q) } }

    Map have = state.subscribed ?: [:]
    have.keySet().findAll { !want.containsKey(it) }.each { f ->
        try {
            interfaces.mqtt.unsubscribe(f)
            logDebug "unsubscribed from ${f}"
        } catch (e) {
            log.error "${device}: could not unsubscribe from ${f}: ${e.message}"
        }
    }
    want.each { f, q ->
        if (have[f] == q) return
        try {
            interfaces.mqtt.subscribe(f, q)
            logDebug "subscribed to ${f} (qos ${q})"
        } catch (e) {
            log.error "${device}: could not subscribe to ${f}: ${e.message}"
        }
    }
    state.subscribed = want
}

// Returns a problem description, or null if the topic filter is valid.
private static String checkTopic(String topic) {
    if (!topic?.trim()) return "a topic is needed"
    List<String> parts = topic.trim().split("/", -1) as List
    for (int i = 0; i < parts.size(); i++) {
        String p = parts[i]
        if (p.contains("#") && (p != "#" || i != parts.size() - 1)) return "# must be the whole last level"
        if (p.contains("+") && p != "+") return "+ must be a whole level"
    }
    return null
}

private static int toQos(qos) {
    try {
        int q = qos as int
        return Math.max(0, Math.min(2, q))
    } catch (ignored) {
        return 1
    }
}

// Loads the topic list into state.topics the first time it's needed.
//
// Hubitat clears a device's state when its driver changes, so the list is also
// kept in the device data "topicList" (written by listTopics), which survives.
// Sources, in order:
//   1. device data "topicList" (this driver, before a driver change)
//   2. state.handlers (the old parasaurolophus driver: [topic: [id: dni, qos: n]])
//   3. the latest "topic" event on each child (the old driver sent one)
// Children still without a topic show up in the app as needing one.
private void loadTopics() {
    if (state.topics != null) return
    Map topics = [:]
    String saved = device.getDataValue("topicList")
    if (saved) {
        try {
            new groovy.json.JsonSlurper().parseText(saved).each { r ->
                if (r.dni && r.topic) topics[r.dni] = [topic: r.topic, qos: toQos(r.qos)]
            }
        } catch (e) {
            log.warn "${device}: couldn't read saved topic list: ${e.message}"
        }
    }
    (state.handlers ?: [:]).each { topic, h ->
        if (h?.id && !topics[h.id]) topics[h.id] = [topic: topic, qos: toQos(h.qos)]
    }
    getChildDevices().each { child ->
        if (topics[child.deviceNetworkId]) return
        try {
            def evt = child.events(max: 50).find { it.name == "topic" }
            if (evt?.value) topics[child.deviceNetworkId] = [topic: evt.value, qos: 1]
        } catch (ignored) {}
    }
    state.topics = topics
    ["handlers", "reconnect", "connected"].each { state.remove(it) }
    List missing = getChildDevices().findAll { !topics[it.deviceNetworkId] }*.displayName
    log.info "${device}: loaded ${topics.size()} topic(s)" + (missing ? "; no topic known for: ${missing.join(', ')}" : "")
    listTopics()
    // First run on this driver (e.g. right after a driver change): start cleanly
    // so subscriptions and the connection watchdog are set up.
    if (settings.broker?.trim() && state.wantConnected == null) runIn(2, "initialize")
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device}: ${msg}"
}
