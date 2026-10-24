var Runnable = Java.type("java.lang.Runnable");
var Thread = Java.type("java.lang.Thread");
var Server = Java.type("org.h2.tools.Server");
var ScriptConsole = Java.type("com.killcoding.jsplus.ScriptConsole");
var System = Java.type("java.lang.System");

function H2Server(){
    this.ca = Console.getGlobalCacheArray(0,100);
    var the = this;
    the.h2server = null;
    var CallbackRun = Java.extend(Runnable,{
    	run: function(){
    		Thread.currentThread().setName("H2Server");
    		var cmd = the.ca.getObject() || null;
    		if(cmd !== null) {
    		    if(cmd[0] === 'start') {
    		        print("Server -> " + cmd);
    		        the.h2server = Server.createTcpServer("-tcpPort", cmd[1],"-tcpAllowOthers","-baseDir", cmd[2]).start();
    		    }
    		    
    		    if(cmd[0] === 'stop') {
    		        Console.getContext().get("server").h2server.stop();
    		    }
    		    
    		    if(cmd[0] === 'shutdown') {
    		        Console.getContext().get("server").h2server.stop();
    		        System.exit(0);
    		    }    		    
    		}
    	}
    });
    Console.callback(new CallbackRun(),false);
}

H2Server.prototype.start = function(){
    print("Starting...");
    var msg = "Starting...\n";
    var port = ScriptConsole.getAppenv().getString("H2_PORT","9092");
    var baseDir = ScriptConsole.getAppenv().getString("H2_BASE_DIR");
    this.ca.add(["start",port,baseDir]);
    msg += "Started.\n";
    print("Started.");
    return msg;
}

H2Server.prototype.stop = function(){
    print("Stopping...");
    var msg = "Stopping...\n";
    this.ca.add(["stop"]);
    msg += "Stopped.\n";
    print("Stopped.");
    return msg;
}

H2Server.prototype.restart = function(){
    var msg = this.stop();
    Thread.sleep(1000);
    msg += this.start();
    return msg;
}

H2Server.prototype.shutdown = function(){
    print("Stopping...");
    var msg = "Stopping...\n";
    this.ca.add(["shutdown"]);
    msg += "Shutdown.\n";
    print("Shutdown.");
    return msg;
}


if(OriginConsole.getContext().get("server") === null && OriginConsole !== null){
    OriginConsole.getContext().put("server",new H2Server());
}

