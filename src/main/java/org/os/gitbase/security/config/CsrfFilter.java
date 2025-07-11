package org.os.gitbase.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.os.gitbase.constant.Constant;
import org.springframework.security.web.csrf.CsrfToken;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
@Component
public class CsrfFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());

        if (csrfToken != null) {
            Cookie cookie = new Cookie(Constant.XSRF_TOKEN, csrfToken.getToken());
            cookie.setPath("/");
            cookie.setSecure(true);
            cookie.setHttpOnly(false);
            cookie.setMaxAge(3600);
            response.addCookie(cookie);
        }

        filterChain.doFilter(request, response);
    }
}

