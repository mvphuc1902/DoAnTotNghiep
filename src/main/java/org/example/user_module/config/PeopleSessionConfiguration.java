package org.example.user_module.config;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
@Configuration
public class PeopleSessionConfiguration {
 @Bean ServletListenerRegistrationBean<HttpSessionListener> peopleSessionListener(JdbcTemplate db){return new ServletListenerRegistrationBean<>(new HttpSessionListener(){@Override public void sessionDestroyed(HttpSessionEvent event){try{db.update("DELETE FROM user_presence WHERE session_key=?",PeopleController.sessionKey(event.getSession().getId()));}catch(org.springframework.dao.DataAccessException ignored){/* TTL still expires stale presence if DB is temporarily unavailable. */}}});}
}
