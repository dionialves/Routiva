package br.com.scriptping.ixc;

import br.com.scriptping.ingestion.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClientPingControllerTests {
    private IxcClient.Login login(String ip) throws Exception {
        return new IxcClient.Login(new ObjectMapper().readTree("{\"ip\":\"" + ip + "\",\"senha\":\"secret\"}"), "AA:BB:CC:DD:EE:FF");
    }
    @Test void usesOnlyIxcIpv4AndRestrictsReadAndStopToOwner() throws Exception {
        var ixc = mock(IxcController.class);
        var collector = mock(CollectorClient.class);
        when(collector.enabled()).thenReturn(true);
        when(ixc.login("host", "aabbccddeeff")).thenReturn(ResponseEntity.ok(List.of(login("192.0.2.15"))));
        var mvc = MockMvcBuilders.standaloneSetup(new ClientPingController(ixc, new ClientPingSessions(), collector)).build();
        var owner = new MockHttpSession();
        var response = mvc.perform(post("/api/hosts/host/wireless/ping").session(owner)
            .param("mac", "aabbccddeeff").param("ip", "203.0.113.10"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.ip").value("192.0.2.15"))
            .andExpect(jsonPath("$.samples").isEmpty()).andReturn();
        String id = new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/hosts/host/wireless/ping/{id}", id).session(new MockHttpSession()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/hosts/host/wireless/ping/{id}", id).session(owner)).andExpect(status().isOk());
        mvc.perform(delete("/api/hosts/host/wireless/ping/{id}", id).session(owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/hosts/host/wireless/ping/{id}", id).session(owner)).andExpect(status().isNotFound());
        verify(ixc).login("host", "aabbccddeeff");
    }
    @Test void refusesMissingIpv4AndAmbiguousAddresses() throws Exception {
        var ixc = mock(IxcController.class);
        var collector = mock(CollectorClient.class);
        when(collector.enabled()).thenReturn(true);
        var mvc = MockMvcBuilders.standaloneSetup(new ClientPingController(ixc, new ClientPingSessions(), collector)).build();
        for (var logins : List.of(List.<IxcClient.Login>of(), List.of(login("::1")), List.of(login("192.0.2.15"), login("192.0.2.16")))) {
            when(ixc.login("host", "aabbccddeeff")).thenReturn(ResponseEntity.ok(logins));
            mvc.perform(post("/api/hosts/host/wireless/ping").param("mac", "aabbccddeeff"))
                .andExpect(status().isUnprocessableEntity());
        }
    }
}
