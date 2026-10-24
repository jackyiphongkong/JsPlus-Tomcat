var Runnable = Java.type("java.lang.Runnable");
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
var GhostConsole = Java.type("com.killcoding.jsplus.GhostConsole");
var String = Java.type("java.lang.String");
var Thread = Java.type("java.lang.Thread");
var Runnable = Java.type("java.lang.Runnable");
var HashMap = Java.type("java.util.HashMap");
var GhostConsole = Java.type("com.killcoding.jsplus.GhostConsole");
var Server = Java.type("org.h2.tools.Server");


var log = LoggerFactory.getLogger("main.js");
Logger.systemInfo("main.js","Starting...");
var jsPlusPath = System.getProperty("JS_PLUS_PATH");

var H2StartConsole = Console.newBuilder();
H2StartConsole.getContext().put("OriginConsole",H2StartConsole);
H2StartConsole.eval(jsPlusPath + '/script/H2Server.js');   
    
var H2StopConsole = Console.newBuilder();
H2StopConsole.getContext().put("OriginConsole",H2StartConsole);
H2StopConsole.eval(jsPlusPath + '/script/H2Server.js');

var H2ShutdownConsole = Console.newBuilder();
H2ShutdownConsole.getContext().put("OriginConsole",H2StartConsole);
H2ShutdownConsole.eval(jsPlusPath + '/script/H2Server.js');

function start(){
    var msg = H2StartConsole.getContext().get('server').start();
    return msg;
}

function stop(){
    H2StopConsole.getContext().put("server",H2StartConsole.getContext().get("server"));
    var msg = H2StopConsole.getContext().get('server').stop();
    return msg;
}

function shutdown(){
    H2ShutdownConsole.getContext().put("server",H2StartConsole.getContext().get("server"));
    var msg = H2ShutdownConsole.getContext().get('server').shutdown();
    return msg;
}

function restart(){
    var msg = stop();
    Thread.sleep(1000);
    msg += start();
    return msg;
}

start();

var ghostName = Console.getConsoleVariables().getString("GhostName",null);

if(ghostName !== null){
    print('Starting ' + ghostName + '...');
    var ghostConsole = new GhostConsole(Console,ghostName);
    ghostConsole.start(true);
    print('Started ' + ghostName);
}