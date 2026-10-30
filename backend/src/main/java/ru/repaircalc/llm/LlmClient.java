package ru.repaircalc.llm;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LlmClient {
    private final URI base;private final String token;private final Duration timeout;private final HttpClient client;private final JsonMapper json;private final int maxBytes;
    public LlmClient(JsonMapper json,@Value("${LLM_SERVICE_URL:http://127.0.0.1:8010}")String url,
            @Value("${INTERNAL_SERVICE_TOKEN:}")String token,@Value("${LLM_CONNECT_TIMEOUT_SECONDS:3}")int connect,
            @Value("${LLM_REQUEST_TIMEOUT_SECONDS:1260}")int timeout,@Value("${LLM_RESPONSE_MAX_BYTES:1048576}")int maxBytes){
        this.base=URI.create(url.endsWith("/")?url:url+"/");
        if(!java.util.Set.of("http","https").contains(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || connect<1 || timeout<1 || timeout>3600 || maxBytes<1024)throw new IllegalArgumentException("Invalid LLM transport settings");
        this.token=token;this.timeout=Duration.ofSeconds(timeout);this.maxBytes=maxBytes;this.json=json;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connect)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public boolean configured(){return token.length()>=32;}
    public JsonNode call(String operation,Object body){
        if(!configured())throw new Failure(503,"llm_unavailable");
        var request=HttpRequest.newBuilder(base.resolve("internal/v1/"+operation)).timeout(timeout).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try{
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){
                byte[] bytes=stream.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw new Failure(502,"invalid_model_response");
                JsonNode result=json.readTree(bytes);
                if(response.statusCode()!=200)throw new Failure(response.statusCode(),response.statusCode()==429?"model_busy":result.path("code").asText("llm_unavailable"));
                if(result==null||!result.isObject())throw new Failure(502,"invalid_model_response");return result;
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(503,"llm_unavailable");}
        catch(java.io.IOException|tools.jackson.core.JacksonException e){throw new Failure(503,"llm_unavailable");}
    }
    public static class Failure extends RuntimeException {
        public final int status;public final String code;
        public Failure(int status,String code){super(code);this.status=status;this.code=code;}
    }
}
