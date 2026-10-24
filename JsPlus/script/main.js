var Runnable = Java.type("java.lang.Runnable");
var Thread = Java.type("java.lang.Thread");
var NetworkServerControl = Java.type("org.apache.derby.drda.NetworkServerControl");
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


var log = LoggerFactory.getLogger("main.js");
Logger.systemInfo("main.js","Starting...");
var jsPlusPath = System.getProperty("JS_PLUS_PATH");

var TomcatServerConsole = null;

Thread.sleep(5000);
var ghostConsole = new GhostConsole(Console,'JsPlus_Sample_Ghost');
ghostConsole.start(true);

function TomcatStop(){
    var msg = "";
    if(TomcatServerConsole !== null){
        var tomcat = TomcatServerConsole.getContext().get("tomcat");
        if(tomcat != null){
            msg += "Stopping...\n";
            print("Stopping...");
            tomcat.stop();
            msg += "Stopped.\n";
            print("Stopped.");
            tomcat = null;
            TomcatServerConsole = null;
        }
        return msg;       
    }
    print("ERROR: Tomcat server is not running.");
    return "ERROR: Tomcat server is not running.\n";
}

function TomcatRestart(){
    var msg = TomcatStop();
    msg += TomcatStart();
    return msg;
}

function TomcatStart(){
    if(TomcatServerConsole === null){
        var msg = "Starting...\n";
        print("Starting...");
        TomcatServerConsole = Console.newBuilder();
        TomcatServerConsole.eval(jsPlusPath + '/script/TomcatServer.js');
        msg += "Started.\n";
        print("Started");
        return msg;       
    }
    print("ERROR: Tomcat server is running.");
    return "ERROR: Tomcat server is running.\n";
}


TomcatStart();

print("Hello World!");
