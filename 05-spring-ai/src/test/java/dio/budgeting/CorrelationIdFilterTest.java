package dio.budgeting;

import dio.budgeting.infrastructure.http.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for CorrelationIdFilter (TASK-011): no Spring context, the
 * filter is exercised directly against mock servlet request/response/chain.
 * Covers header preservation/generation/replacement, MDC population and
 * cleanup, and behavior on both success and error/exception paths.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void validHeader_isPreserved_andReturnedInResponse() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "client-supplied-token.001");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("client-supplied-token.001");
    }

    @Test
    void missingHeader_generatesUuid() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }

    @Test
    void invalidHeader_tooLong_isReplacedWithUuid() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "a".repeat(65));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotEqualTo("a".repeat(65));
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }

    @Test
    void invalidHeader_unsafeCharacters_isReplacedWithUuid() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "token with spaces\nand newline");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(java.util.UUID.fromString(generated)).isNotNull();
    }

    @Test
    void blankHeader_isReplacedWithUuid() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "   ");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(java.util.UUID.fromString(response.getHeader(CorrelationIdFilter.HEADER))).isNotNull();
    }

    @Test
    void mdcIsPopulated_duringChainExecution() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "during-chain-token");
        var response = new MockHttpServletResponse();

        var mdcValueDuringChain = new String[1];
        FilterChain chain = (req, res) -> mdcValueDuringChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);

        filter.doFilter(request, response, chain);

        assertThat(mdcValueDuringChain[0]).isEqualTo("during-chain-token");
    }

    @Test
    void mdcIsRemoved_afterRequestCompletes() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcIsRemoved_evenWhenChainThrows() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doThrow(new ServletException("downstream failure")).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(request, response, chain);
        }
        catch (ServletException expected) {
            // propagation is expected and fine - the assertion below is what matters
        }

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void errorResponse_stillReceivesCorrelationHeader() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void consecutiveRequests_doNotReuseEachOthersCorrelationId() throws ServletException, IOException {
        var request1 = new MockHttpServletRequest();
        request1.addHeader(CorrelationIdFilter.HEADER, "first-request-token");
        var response1 = new MockHttpServletResponse();
        filter.doFilter(request1, response1, mock(FilterChain.class));

        var request2 = new MockHttpServletRequest();
        var response2 = new MockHttpServletResponse();
        var mdcDuringSecond = new String[1];
        FilterChain chain2 = (req, res) -> mdcDuringSecond[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
        filter.doFilter(request2, response2, chain2);

        assertThat(mdcDuringSecond[0]).isNotEqualTo("first-request-token");
    }
}
