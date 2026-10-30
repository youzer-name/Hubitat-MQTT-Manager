/**
 *  MQTT Manager (app)
 *
 *  Version 0.1.0
 *  https://github.com/youzer-name/Hubitat-MQTT-Manager (MIT License)
 *
 *  Sets up one MQTT broker connection and lets you add, edit and delete the
 *  topic devices that receive its messages.
 *
 *  The connection itself lives in an "MQTT Manager Connection" device, because
 *  only drivers can talk MQTT. This app either creates that device or adopts
 *  one you already have (for example, one switched over from the old
 *  parasaurolophus "MQTT Connection" driver, which keeps its device IDs).
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String NAMESPACE = "youzer-name"
@Field static final String CONN_DRIVER = "MQTT Manager Connection"

definition(
    name: "MQTT Manager",
    namespace: "youzer-name",
    author: "youzer-name",
    description: "Connect to an MQTT broker and manage topic devices",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/youzer-name/Hubitat-MQTT-Manager/main/MqttManagerApp.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "brokerPage")
    page(name: "addPage")
    page(name: "editPage")
    page(name: "deletePage")
}

// ---------------------------------------------------------------------------
// Lifecycle

void installed() {
    initialize()
}

void updated() {
    initialize()
}

void initialize() {
    if (settings.setupMode == "new" && !state.connDni && settings.newBroker?.trim()) {
        createConnection()
    }
}

void uninstalled() {
    // A connection created by this app is removed with it; an adopted one is left alone.
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

private void createConnection() {
    String dni = "mqtt-manager-${app.id}"
    def conn = getChildDevice(dni)
    if (!conn) {
        String label = app.label != "MQTT Manager" && app.label ? app.label : "MQTT Connection"
        conn = addChildDevice(NAMESPACE, CONN_DRIVER, dni, [name: CONN_DRIVER, label: label, isComponent: false])
    }
    state.connDni = dni
    conn.updateSetting("broker", [value: settings.newBroker.trim(), type: "text"])
    conn.updateSetting("clientId", [value: "hubitat-${UUID.randomUUID().toString().take(8)}", type: "text"])
    if (settings.newUser?.trim()) conn.updateSetting("username", [value: settings.newUser.trim(), type: "text"])
    if (settings.newPass) conn.updateSetting("password", [value: settings.newPass, type: "password"])
    conn.updateSetting("lwtTopic", [value: "hubitat/lwt", type: "text"])
    conn.updateSetting("lwtMessage", [value: "disconnected", type: "text"])
    ["newBroker", "newUser", "newPass"].each { app.removeSetting(it) }
    conn.initialize()
    log.info "Created ${conn} for ${conn.getSetting('broker')}"
}

// The connection device: one this app created, or one picked by the user.
private getConn() {
    if (state.connDni) return getChildDevice(state.connDni)
    if (settings.setupMode == "existing") return settings.existingConn
    return null
}

private boolean connReady(conn) {
    return conn && conn.hasCommand("addTopic")
}

// Asks the connection device to save a fresh topic list in its device data,
// then reads it. (An app doesn't get return values from commands on devices
// it doesn't own, so the list comes through device data.)
private List<Map> topicRows(conn) {
    try {
        conn.listTopics()
        String json = conn.getDataValue("topicList")
        def rows = json ? new JsonSlurper().parseText(json) : null
        return rows instanceof List ? (rows as List<Map>) : []
    } catch (e) {
        log.error "Could not read topic list from ${conn}: ${e.message}"
        return []
    }
}

private Map topicOptions(List<Map> rows) {
    rows.collectEntries { [(it.dni): "${it.name}  (${it.topic ?: 'no topic'})"] }
}

// ---------------------------------------------------------------------------
// Pages

def mainPage() {
    // Each visit to a sub-page starts from the device's current values.
    ["addName", "addTopic", "addQos", "editDni", "editName", "editTopic", "editQos", "delDni",
     "cfgBroker", "cfgClientId", "cfgUser", "cfgPass", "cfgClearPass", "cfgLwtTopic", "cfgLwtMessage"]
        .each { app.removeSetting(it) }
    state.remove("editLoaded")
    state.remove("brokerLoaded")
    state.remove("msg")

    def conn = getConn()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section() { paragraph pageHeader("MQTT Manager") }
        if (!conn) {
            setupSections()
        } else if (!connReady(conn)) {
            section("Connection") {
                paragraph warn("<b>${esc(conn.displayName)}</b> isn't using the <b>${CONN_DRIVER}</b> driver yet. " +
                    "Open <a href='/device/edit/${conn.id}' target='_blank'>its device page</a>, change <b>Type</b> " +
                    "to <b>${CONN_DRIVER}</b>, click <b>Save Device</b>, then come back here. " +
                    "Its topic devices and their IDs stay the same.")
                if (!state.connDni) input "existingConn", "capability.initialize", title: "Connection device",
                                          required: true, submitOnChange: true
            }
        } else {
            List<Map> rows = topicRows(conn)
            section("Connection") {
                paragraph connectionSummary(conn)
                href "brokerPage", title: "Broker settings", description: "Address, login and last-will message"
                input "btnReconnect", "button", title: "Reconnect"
            }
            section("Topic devices (${rows.size()})") {
                List missing = rows.findAll { !it.topic }
                if (missing) paragraph warn("No topic is set for <b>${missing.collect { esc(it.name) }.join(', ')}</b>, " +
                                            "so it won't get messages. Use <b>Edit a topic device</b> to set one.")
                paragraph rows ? topicTable(rows) : "<i>No topic devices yet.</i>"
                href "addPage", title: "Add a topic device"
                if (rows) {
                    href "editPage", title: "Edit a topic device"
                    href "deletePage", title: "Delete a topic device"
                }
            }
            section("App") {
                label title: "App name (optional)", required: false
                if (!state.connDni) input "existingConn", "capability.initialize", title: "Connection device",
                                          required: true, submitOnChange: true
            }
        }
    }
}

private void setupSections() {
    section("Set up") {
        input "setupMode", "enum", title: "Connection", required: true, submitOnChange: true,
              options: ["new": "Create a new connection", "existing": "Use an existing connection device"]
    }
    if (settings.setupMode == "new") {
        section("Broker") {
            input "newBroker", "text", title: "Broker URL", description: "tcp://host:1883 or ssl://host:8883",
                  required: true, submitOnChange: true
            input "newUser", "text", title: "Username (optional)", required: false
            input "newPass", "password", title: "Password (optional)", required: false
            paragraph "Click <b>Done</b> to create the connection device, then open this app again to add topic devices."
        }
    } else if (settings.setupMode == "existing") {
        section("Connection device") {
            input "existingConn", "capability.initialize", title: "Connection device", required: true, submitOnChange: true
            paragraph "Pick your MQTT connection device. If it still uses the old " +
                      "<b>MQTT Connection</b> driver, you'll be shown how to switch it over."
        }
    }
}

def brokerPage() {
    def conn = getConn()
    if (!state.brokerLoaded && conn) {
        app.updateSetting("cfgBroker", [value: conn.getSetting("broker") ?: "", type: "text"])
        app.updateSetting("cfgClientId", [value: conn.getSetting("clientId") ?: "", type: "text"])
        app.updateSetting("cfgUser", [value: conn.getSetting("username") ?: "", type: "text"])
        app.updateSetting("cfgLwtTopic", [value: conn.getSetting("lwtTopic") ?: "hubitat/lwt", type: "text"])
        app.updateSetting("cfgLwtMessage", [value: conn.getSetting("lwtMessage") ?: "disconnected", type: "text"])
        app.removeSetting("cfgPass")
        app.updateSetting("cfgClearPass", [value: "false", type: "bool"])
        state.brokerLoaded = true
    }
    boolean hasPass = conn?.getSetting("password") as boolean
    dynamicPage(name: "brokerPage", title: "") {
        section() { paragraph pageHeader("Broker settings") + flash() }
        section() {
            input "cfgBroker", "text", title: "Broker URL", description: "tcp://host:1883 or ssl://host:8883", required: true
            input "cfgClientId", "text", title: "Client ID", description: "Must be unique on the broker", required: true
            input "cfgUser", "text", title: "Username", required: false
            input "cfgPass", "password", title: hasPass ? "New password (leave blank to keep the current one)" : "Password",
                  required: false
            if (hasPass) input "cfgClearPass", "bool", title: "Remove the password", required: false
            input "cfgLwtTopic", "text", title: "Last-will topic", required: false
            input "cfgLwtMessage", "text", title: "Last-will message", required: false
        }
        section() { input "btnSaveBroker", "button", title: "Save and reconnect" }
    }
}

def addPage() {
    def conn = getConn()
    dynamicPage(name: "addPage", title: "") {
        section() { paragraph pageHeader("Add a topic device") + flash() }
        section() {
            input "addName", "text", title: "Device name", required: false
            input "addTopic", "text", title: "Topic", required: false,
                  description: "e.g. home/doorbell. Wildcards: + for one level, # for the rest"
            input "addQos", "enum", title: "QoS", options: qosOptions(), defaultValue: "1", required: false
        }
        section() { input "btnAdd", "button", title: "Add device" }
    }
}

def editPage() {
    def conn = getConn()
    List<Map> rows = conn ? topicRows(conn) : []
    Map row = rows.find { it.dni == settings.editDni }
    if (row && state.editLoaded != row.dni) {
        app.updateSetting("editName", [value: row.name ?: "", type: "text"])
        app.updateSetting("editTopic", [value: row.topic ?: "", type: "text"])
        app.updateSetting("editQos", [value: (row.qos ?: 1).toString(), type: "enum"])
        state.editLoaded = row.dni
    }
    dynamicPage(name: "editPage", title: "") {
        section() { paragraph pageHeader("Edit a topic device") + flash() }
        section() {
            input "editDni", "enum", title: "Device", options: topicOptions(rows), required: false, submitOnChange: true
        }
        if (row) {
            section() {
                paragraph "<span style='color:#666'>Device ID ${row.id} &middot; " +
                          "<a href='/device/edit/${row.id}' target='_blank'>open device page</a></span>"
                input "editName", "text", title: "Device name", required: false
                input "editTopic", "text", title: "Topic", required: false,
                      description: "Wildcards: + for one level, # for the rest"
                input "editQos", "enum", title: "QoS", options: qosOptions(), required: false
            }
            section() { input "btnSave", "button", title: "Save changes" }
        }
    }
}

def deletePage() {
    def conn = getConn()
    List<Map> rows = conn ? topicRows(conn) : []
    Map row = rows.find { it.dni == settings.delDni }
    dynamicPage(name: "deletePage", title: "") {
        section() { paragraph pageHeader("Delete a topic device") + flash() }
        section() {
            input "delDni", "enum", title: "Device", options: topicOptions(rows), required: false, submitOnChange: true
        }
        if (row) {
            section() {
                paragraph warn("This deletes <b>${esc(row.name)}</b> (device ID ${row.id}, topic " +
                               "<code>${esc(row.topic)}</code>). Rules that use it will stop working.")
                input "btnDelete", "button", title: "Delete ${row.name}"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons

void appButtonHandler(String btn) {
    def conn = getConn()
    if (!connReady(conn)) return
    switch (btn) {
        case "btnReconnect":
            conn.initialize()
            break

        case "btnAdd":
            String err = topicProblem(settings.addTopic) ?: (settings.addName?.trim() ? null : "Enter a device name.")
            if (err) { state.msg = [ok: false, text: err]; break }
            Set before = topicRows(conn)*.dni as Set
            conn.addTopic(settings.addName.trim(), settings.addTopic.trim(), (settings.addQos ?: "1") as int)
            if (topicRows(conn).any { !(it.dni in before) }) {
                state.msg = [ok: true, text: "Added <b>${esc(settings.addName.trim())}</b> on <code>${esc(settings.addTopic.trim())}</code>."]
                ["addName", "addTopic", "addQos"].each { app.removeSetting(it) }
            } else {
                state.msg = [ok: false, text: "Couldn't add the device. See Logs for details."]
            }
            break

        case "btnSave":
            if (!settings.editDni) break
            String err = topicProblem(settings.editTopic) ?: (settings.editName?.trim() ? null : "Enter a device name.")
            if (err) { state.msg = [ok: false, text: err]; break }
            conn.updateTopic(settings.editDni, settings.editName.trim(), settings.editTopic.trim(),
                             (settings.editQos ?: "1") as int)
            Map after = topicRows(conn).find { it.dni == settings.editDni }
            boolean ok = after && after.topic == settings.editTopic.trim() && after.name == settings.editName.trim()
            state.msg = ok ? [ok: true, text: "Saved."] : [ok: false, text: "Couldn't save. See Logs for details."]
            break

        case "btnDelete":
            if (!settings.delDni) break
            String name = topicRows(conn).find { it.dni == settings.delDni }?.name ?: "device"
            conn.removeTopic(settings.delDni)
            boolean ok = !topicRows(conn).any { it.dni == settings.delDni }
            state.msg = ok ? [ok: true, text: "Deleted <b>${esc(name)}</b>."] : [ok: false, text: "Couldn't delete. See Logs for details."]
            app.removeSetting("delDni")
            break

        case "btnSaveBroker":
            if (!settings.cfgBroker?.trim() || !settings.cfgClientId?.trim()) {
                state.msg = [ok: false, text: "Broker URL and client ID are needed."]
                break
            }
            if (!(settings.cfgBroker.trim() ==~ /(?i)^(tcp|ssl|ws|wss):\/\/.+/)) {
                state.msg = [ok: false, text: "The broker URL should start with tcp:// or ssl://"]
                break
            }
            conn.updateSetting("broker", [value: settings.cfgBroker.trim(), type: "text"])
            conn.updateSetting("clientId", [value: settings.cfgClientId.trim(), type: "text"])
            conn.updateSetting("username", [value: settings.cfgUser?.trim() ?: "", type: "text"])
            if (settings.cfgClearPass) conn.updateSetting("password", [value: "", type: "password"])
            else if (settings.cfgPass) conn.updateSetting("password", [value: settings.cfgPass, type: "password"])
            conn.updateSetting("lwtTopic", [value: settings.cfgLwtTopic?.trim() ?: "hubitat/lwt", type: "text"])
            conn.updateSetting("lwtMessage", [value: settings.cfgLwtMessage ?: "disconnected", type: "text"])
            app.removeSetting("cfgPass")
            app.updateSetting("cfgClearPass", [value: "false", type: "bool"])
            conn.initialize()
            state.msg = [ok: true, text: "Saved. Reconnecting&hellip;"]
            break
    }
}

// Same rules as the driver, checked here so the page can say what's wrong.
private static String topicProblem(String topic) {
    if (!topic?.trim()) return "Enter a topic."
    List<String> parts = topic.trim().split("/", -1) as List
    for (int i = 0; i < parts.size(); i++) {
        String p = parts[i]
        if (p.contains("#") && (p != "#" || i != parts.size() - 1)) return "# can only be used as the whole last level, like <code>home/#</code>."
        if (p.contains("+") && p != "+") return "+ must be a whole level, like <code>home/+/state</code>."
    }
    return null
}

// ---------------------------------------------------------------------------
// HTML helpers

private static Map qosOptions() {
    ["0": "0 - at most once", "1": "1 - at least once", "2": "2 - exactly once"]
}

private static String pageHeader(String title) {
    "<div style='font-size:1.4em;font-weight:600;margin-bottom:4px'>${title}</div>"
}

private String flash() {
    Map m = state.msg
    if (!m) return ""
    state.remove("msg")
    String color = m.ok ? "#1b7f3b" : "#b3261e"
    String bg = m.ok ? "#e7f5ec" : "#fdecea"
    return "<div style='margin-top:8px;padding:8px 12px;border-radius:6px;background:${bg};color:${color}'>${m.text}</div>"
}

private static String warn(String html) {
    "<div style='padding:8px 12px;border-radius:6px;background:#fff4e5;color:#7a4b00'>${html}</div>"
}

private static String esc(s) {
    s == null ? "" : s.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

private String connectionSummary(conn) {
    boolean up = conn.currentValue("connection") == "connected"
    String dot = "<span style='display:inline-block;width:10px;height:10px;border-radius:50%;" +
                 "background:${up ? '#2e9e4f' : '#c62828'};margin-right:6px'></span>"
    String broker = conn.getSetting("broker") ?: "<i>not set</i>"
    return "<div style='line-height:1.7'>${dot}<b>${up ? 'Connected' : 'Disconnected'}</b>" +
           " &middot; <code>${esc(broker)}</code><br>" +
           "<span style='color:#666'>Device: <a href='/device/edit/${conn.id}' target='_blank'>${esc(conn.displayName)}</a>" +
           " (ID ${conn.id})</span></div>"
}

private String topicTable(List<Map> rows) {
    String th = "style='text-align:left;padding:6px 10px;border-bottom:2px solid #ccc;white-space:nowrap'"
    String td = "style='padding:6px 10px;border-bottom:1px solid #eee;vertical-align:top'"
    StringBuilder sb = new StringBuilder()
    sb << "<div style='overflow-x:auto'><table style='border-collapse:collapse;width:100%;font-size:0.95em'>"
    sb << "<tr><th ${th}>Device</th><th ${th}>Topic</th><th ${th}>QoS</th><th ${th}>Last message</th><th ${th}>When</th></tr>"
    rows.each { r ->
        String payload = r.payload == null ? "<i style='color:#999'>none</i>" : "<code>${esc(trunc(r.payload, 40))}</code>"
        sb << "<tr>"
        sb << "<td ${td}><a href='/device/edit/${r.id}' target='_blank'>${esc(r.name)}</a>" +
              "<br><span style='color:#999;font-size:0.85em'>ID ${r.id}</span></td>"
        sb << "<td ${td}>" + (r.topic ? "<code>${esc(r.topic)}</code>" :
              "<span style='color:#b3261e'>not set</span>") + "</td>"
        sb << "<td ${td}>${r.qos ?: 0}</td>"
        sb << "<td ${td}>${payload}</td>"
        sb << "<td style='padding:6px 10px;border-bottom:1px solid #eee;vertical-align:top;white-space:nowrap'>" +
              "${ago(r.lastReceived as Long)}</td>"
        sb << "</tr>"
    }
    sb << "</table></div>"
    return sb.toString()
}

private static String trunc(s, int n) {
    String t = s.toString()
    t.length() > n ? t.take(n - 3) + "..." : t
}

private static String ago(Long ms) {
    if (!ms) return "<span style='color:#999'>never</span>"
    long s = (long) ((new Date().time - ms) / 1000)
    if (s < 60) return "just now"
    if (s < 3600) return "${(int) (s / 60)} min ago"
    if (s < 86400) return "${(int) (s / 3600)} h ago"
    return "${(int) (s / 86400)} d ago"
}
