var Runnable = Java.type("java.lang.Runnable");
var Thread = Java.type("java.lang.Thread");
var HashMap = Java.type("java.util.HashMap");
var TomcatServer = Java.type("com.killcoding.jsplus.tomcat.TomcatServer");
var Thread = Java.type("java.lang.Thread");
var Logger = Java.type("com.killcoding.log.Logger");
var LoggerFactory = Java.type("com.killcoding.log.LoggerFactory");
var LogRecord = Java.type("com.killcoding.log.LogRecord");
var DiskFile = Java.type("com.killcoding.file.DiskFile");
var Exception = Java.type("java.lang.Exception");
var SimpleDateFormat = Java.type("java.text.SimpleDateFormat");
var DriverDataSource = Java.type("com.killcoding.datasource.DriverDataSource");
var System = Java.type("java.lang.System");
var ScriptConsole = Java.type("com.killcoding.jsplus.ScriptConsole");
var String = Java.type("java.lang.String");
var Thread = Java.type("java.lang.Thread");
var System = Java.type("java.lang.System");
var GhostConsole = Java.type("com.killcoding.jsplus.GhostConsole");


var log = LoggerFactory.getLogger("main.js");
Logger.systemInfo("main.js","Starting...");
var jsPlusPath = System.getProperty("JS_PLUS_PATH");


var TomcatConsole = Console.newBuilder();
TomcatConsole.getContext().put("OriginConsole",Console);
TomcatConsole.eval(jsPlusPath + '/script/TomcatEmbed.js');

var TomcatStartConsole = Console.newBuilder();
TomcatStartConsole.getContext().put("tomcat",Console.getContext().get("tomcat"));

var TomcatStopConsole = Console.newBuilder();
TomcatStopConsole.getContext().put("tomcat",Console.getContext().get("tomcat"));

function start(){
    return TomcatStartConsole.evalScript("tomcat.start()");
}

function stop(){
    return TomcatStopConsole.evalScript("tomcat.stop()");
}

function showMemoryInfo(){
   var info = TomcatServer.getMemoryInfo();
   return String.format("{total: %s, max: %s, used: %s, free: %s}",info.total,info.max,info.used,info.free);
}


var ghostName = Console.getConsoleVariables().getString("GhostName",null);

if(ghostName !== null){
    print('Starting ' + ghostName + '...');
    var ghostConsole = new GhostConsole(Console,ghostName);
    ghostConsole.start(true);
    print('Started ' + ghostName);
}

start();

