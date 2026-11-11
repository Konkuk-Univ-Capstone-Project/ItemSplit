package com.capstone.itemsplit.settlement;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Apply privacy headers to errors as well as successful secret-bearing responses. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class SettlementSharingPrivacyFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        String path=request.getRequestURI();
        if(path.startsWith("/api/shared/settlements") ||
            path.matches("/api/rooms/[^/]+/settlement-snapshots/[^/]+/share-token")) {
            response.setHeader("Cache-Control","no-store");
            response.setHeader("Referrer-Policy","no-referrer");
        }
        chain.doFilter(request,response);
    }
}
