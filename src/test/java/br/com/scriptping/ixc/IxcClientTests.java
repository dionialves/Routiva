package br.com.scriptping.ixc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IxcClientTests {
    @Test void readsSpeedsFromLoginGroupWithoutUsingWirelessRates() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"total\":1,\"registros\":[{\"id\":\"7\",\"download\":\"100M\",\"upload\":\"50M\"}]}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var client = new IxcClient("https://ixc.example", "token", new ObjectMapper(), http);
        var plan = client.findPlan("7");
        assertEquals("100M", plan.download());
        assertEquals("50M", plan.upload());
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("https://ixc.example/webservice/v1/radgrupos", captor.getValue().uri().toString());
    }

    private final HttpClient http = mock(HttpClient.class);
    private final IxcClient client = new IxcClient("https://ixc.example/webservice/v1/", "7:secret", new ObjectMapper(), http);

    @Test void normalizesSupportedMacFormatsAndRejectsPartialValues() {
        for (String mac : new String[]{"aa:bb:cc:dd:ee:ff", "aa-bb-cc-dd-ee-ff", "aabb.ccdd.eeff", "aabbccddeeff"})
            assertEquals("AA:BB:CC:DD:EE:FF", IxcClient.normalizeMac(mac));
        assertThrows(ResponseStatusException.class, () -> client.findByMac("AA:BB"));
        verifyNoInteractions(http);
    }

    @Test void requestsReadOnlyListingWithBasicAuthAndMapsOnlyExactMatches() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
            {"total":"3","registros":[
            {"id":"1","mac":"aa-bb-cc-dd-ee-ff","ip":"192.0.2.1","login":"cliente","senha":"ppp","senha_router1":"router","porta_http":"8080"},
            {"id":"2","mac":"AA:BB:CC:DD:EE:FF","login":"outro"},
            {"id":"3","mac":"00:11:22:33:44:55","senha":"nao-expor"}]}
            """);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var result = client.findByMac("aabbccddeeff");
        assertEquals(2, result.size());
        assertEquals("192.0.2.1", result.getFirst().ip);
        assertEquals("cliente", result.getFirst().pppoe);
        assertEquals("ppp", result.getFirst().pppoePassword);
        assertEquals("router", result.getFirst().equipmentPassword);
        assertEquals("8080", result.getFirst().connectionPort);
        assertEquals("", result.get(1).equipmentPassword);
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captor.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals("https://ixc.example/webservice/v1/radusuarios", captor.getValue().uri().toString());
        assertEquals("listar", captor.getValue().headers().firstValue("ixcsoft").orElseThrow());
        assertEquals("Basic NzpzZWNyZXQ=", captor.getValue().headers().firstValue("Authorization").orElseThrow());
    }

    @Test void rejectsApiErrorsAndTruncatedResultsWithoutExposingResponse() throws Exception {
        for (String body : new String[]{"{\"type\":\"error\",\"message\":\"secret\"}", "{\"total\":100001,\"registros\":[]}"}) {
            var error = assertThrows(ResponseStatusException.class, () -> client.parse(body, "AA:BB:CC:DD:EE:FF"));
            assertEquals(502, error.getStatusCode().value());
            assertFalse(error.getReason().contains("secret"));
        }
        assertTrue(client.parse("{\"total\":0,\"registros\":[]}", "AA:BB:CC:DD:EE:FF").isEmpty());
    }
    @Test void acceptsZeroResultsWithoutRecordsButRejectsMalformedOrErrorResponses() throws Exception {
        for (String body : new String[]{"{\"total\":\"0\"}", "{\"total\":0,\"registros\":null}"})
            assertTrue(client.parse(body, "AA:BB:CC:DD:EE:FF").isEmpty());
        for (String body : new String[]{"{\"total\":1}", "{\"total\":0,\"registros\":{}}",
                "{\"type\":\"error\",\"total\":0}", "{\"type\":\"error\",\"total\":0,\"registros\":[]}"})
            assertThrows(ResponseStatusException.class, () -> client.parse(body, "AA:BB:CC:DD:EE:FF"));
    }

    @Test void listsSharedInventoryForMultipleMacsAndFollowsPagination() throws Exception {
        HttpResponse<String> first = mock(HttpResponse.class), second = mock(HttpResponse.class);
        when(first.statusCode()).thenReturn(200); when(second.statusCode()).thenReturn(200);
        when(first.body()).thenReturn("{\"total\":2,\"registros\":[{\"id\":\"1\",\"mac\":\"AA:BB:CC:DD:EE:01\",\"login\":\"um\"}]}");
        when(second.body()).thenReturn("{\"total\":2,\"registros\":[{\"id\":\"2\",\"mac\":\"AA:BB:CC:DD:EE:02\",\"login\":\"dois\"}]}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(first, second);
        var result = client.findByMacs(java.util.List.of("aabbccddee01", "aabbccddee02", "aabbccddee03", "aabbccddee01"));
        assertEquals(2, result.size());
        var captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(captor.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        for (int page = 0; page < 2; page++) {
            var subscriber = HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
            captor.getAllValues().get(page).bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscriber.onSubscribe(subscription); }
                public void onNext(java.nio.ByteBuffer item) { subscriber.onNext(java.util.List.of(item)); }
                public void onError(Throwable error) { subscriber.onError(error); }
                public void onComplete() { subscriber.onComplete(); }
            });
            var body = new ObjectMapper().readTree(subscriber.getBody().toCompletableFuture().get());
            assertEquals(">", body.path("oper").asText());
            assertEquals("radusuarios.id", body.path("qtype").asText());
            assertEquals("0", body.path("query").asText());
            assertEquals(Integer.toString(page + 1), body.path("page").asText());
        }
    }

}
