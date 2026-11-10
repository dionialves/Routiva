package br.com.scriptping.cleanup;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import static org.mockito.Mockito.*;

class RawCleanupTests {
    @Test void splitWindowsKeepOriginalCursorUntilNextRound() {
        var store = mock(CleanupStore.class);
        when(store.oldest()).thenReturn(100L);
        when(store.latest()).thenReturn(199L);
        when(store.cursor()).thenReturn(1L, 2L);
        when(store.discover(100, 200, 1, 2)).thenThrow(new QueryTimeoutException("timeout"));
        when(store.discover(100, 150, 1, 2)).thenReturn(List.of(1L));
        when(store.discover(100, 200, 2, 2)).thenReturn(List.of(2L));
        var cleanup = new RawCleanup(store, new CleanupQueue(), true, false, 2880, 2);

        cleanup.cleanup();
        verify(store).cursor();
        verify(store).discover(150, 200, 1, 2);
        verify(store).delete(List.of(1L));
        verify(store, never()).delete(List.of(2L));

        cleanup.cleanup();
        verify(store, times(2)).cursor();
        verify(store).delete(List.of(2L));
    }
}
