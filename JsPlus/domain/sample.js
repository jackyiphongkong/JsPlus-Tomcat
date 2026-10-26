var System = Java.type('java.lang.System');
var TomcatScriptConsole = Java.type('com.killcoding.jsplus.tomcat.TomcatScriptConsole');
var GZIPInputStream = Java.type('java.util.zip.GZIPInputStream');
var ByteArrayInputStream = Java.type('java.io.ByteArrayInputStream');
var StandardCharsets = Java.type('java.nio.charset.StandardCharsets');
var String = Java.type('java.lang.String');
var ByteArrayOutputStream = Java.type('java.io.ByteArrayOutputStream');
var GZIPOutputStream = Java.type('java.util.zip.GZIPOutputStream');


function DomainMapping(){
   // java.lang.System.out.println("Request: " + TomcatScriptConsole.get().request);
}

DomainMapping.prototype.getTarget = function(){
    //return "https://zh.wikipedia.org"; 
    return "https://killcoding.com";
}

DomainMapping.prototype.getConnectTimeout = function(){
    return "60s"; 
}

DomainMapping.prototype.getForwardedHeaders = function(){
    return {
        // header_A: 'value_1',
        // header_B: 'value_2',
        // header_C: 'value_3'
    }; 
}

DomainMapping.prototype.getResponseHeaders = function(){
    return {
        // header_1: 'value_1',
        // header_2: 'value_2',
        // header_3: 'value_3'
    }; 
}

DomainMapping.getParams = function(){
    var domain = new DomainMapping();
    return {
        target: domain.getTarget(),
        connectTimeout: domain.getConnectTimeout(),
        forwardedHeaders: domain.getForwardedHeaders(),
        responseHeaders: domain.getResponseHeaders(),
        forwardedHeader: true,
        filterEnable: true
    };
}

DomainMapping.filter = function(){
    var request = TomcatScriptConsole.get().request;
    var response = TomcatScriptConsole.get().response;
    var backendResponse = TomcatScriptConsole.get().backendResponse;
    var backendStream = TomcatScriptConsole.get().backendStream;
    var encoding = backendResponse.headers().firstValue("Content-Encoding").orElse("");
    var type = backendResponse.headers().firstValue("Content-Type").orElse("");
    var responseBytes = null;
    if(type.indexOf('text/html') > -1){ //&& request.getRequestURI() === '/wiki/'
        responseBytes = backendStream.readAllBytes();
        if(encoding === 'gzip'){
            var gzis = new GZIPInputStream(new ByteArrayInputStream(responseBytes));
            var decompressed = gzis.readAllBytes();            
            var text = new String(decompressed, StandardCharsets.UTF_8);
            text = text.concat("<!--Cleared-->");
            var textBytes = StringUtil.toBytes(text);
            var baos = new ByteArrayOutputStream();
            var gzos = new GZIPOutputStream(baos);
            gzos.write(textBytes);
            gzos.close();
            var newBytes = baos.toByteArray();
            response.setContentLength(newBytes.length);
            return newBytes;
        }else{           
            var text = new String(responseBytes, StandardCharsets.UTF_8);
            text = text.concat("<!--Cleared-->");
            var newBytes = StringUtil.toBytes(text);
            response.setContentLength(newBytes.length);
            return newBytes;   
        }
    }
    return responseBytes; 
}

var StringUtil = (function(){
    var StringClass  = Java.type("java.lang.String");
    var CharsetClass = Java.type("java.nio.charset.Charset");
    var StandardCharsets = Java.type("java.nio.charset.StandardCharsets");
    var byteArrayClass = Java.type("byte[]").class;

    var ctorBytesCharset = StringClass.class.getConstructor(byteArrayClass, CharsetClass.class);
    var ctorString       = StringClass.class.getConstructor(StringClass.class);
    var methodConcat     = StringClass.class.getMethod("concat", StringClass.class);
    var methodGetBytes   = StringClass.class.getMethod("getBytes", CharsetClass.class);

    return {
        fromBytes: function(bytes){
            return ctorBytesCharset.newInstance(bytes, StandardCharsets.UTF_8);
        },
        fromJs: function(jsStr){
            return ctorString.newInstance(jsStr);
        },
        concat: function(a, b){
            return methodConcat.invoke(a, StringUtil.fromJs(b));
        },
        // Java String → byte[]
        toBytes: function(str){
            return methodGetBytes.invoke(str, StandardCharsets.UTF_8);
        }
    };
})();
