package org.example.user_module.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PURCHASE_TEST_DB_URL",matches=".+")
class PeopleIntegrationTest {
 @Test void avatarsStayWithTheirAccountAndPresenceAggregatesTabsAndExpires(){
 var db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PURCHASE_TEST_DB_URL"),System.getenv("PURCHASE_TEST_DB_USER"),System.getenv("PURCHASE_TEST_DB_PASSWORD")));
 new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())).execute(status->{status.setRollbackOnly();try{
 if(db.queryForObject("SELECT to_regclass('user_avatars') IS NULL",Boolean.class))db.execute(Files.readString(Path.of("src/main/resources/people-profile-v1.sql")));
 var users=db.queryForList("SELECT id,username FROM users ORDER BY id LIMIT 2");assertEquals(2,users.size(),"Two existing accounts required");long one=((Number)users.get(0).get("id")).longValue(),two=((Number)users.get(1).get("id")).longValue();
 var api=new PeopleController(db);var auth=new UsernamePasswordAuthenticationToken(users.get(0).get("username"),"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
 api.upload(auth,one,PeopleControllerTest.photo(0xff0000));api.upload(auth,two,PeopleControllerTest.photo(0x0000ff));var other=api.getAvatar(two).getBody();api.upload(auth,one,PeopleControllerTest.photo(0x00ff00));assertArrayEquals(other,api.getAvatar(two).getBody());assertFalse(Arrays.equals(other,api.getAvatar(one).getBody()));
 db.update("DELETE FROM user_presence WHERE user_id=?",one);var session=new MockHttpSession();var tab1=UUID.randomUUID();var tab2=UUID.randomUUID();api.heartbeat(auth,session,new PeopleController.Heartbeat(tab1,"ACTIVE"));api.heartbeat(auth,session,new PeopleController.Heartbeat(tab2,"AWAY"));assertEquals("ACTIVE",presence(api,one));api.heartbeat(auth,session,new PeopleController.Heartbeat(tab1,"AWAY"));assertEquals("AWAY",presence(api,one));db.update("UPDATE user_presence SET last_seen=now()-interval '3 minutes' WHERE user_id=?",one);assertEquals("OFFLINE",presence(api,one));api.heartbeat(auth,session,new PeopleController.Heartbeat(tab1,"ACTIVE"));api.leave(auth,session);assertEquals("OFFLINE",presence(api,one));
 }catch(Exception e){throw new RuntimeException(e);}return null;});
 }
 String presence(PeopleController api,long id){return api.people().stream().filter(p->p.get("id").equals(Long.toString(id))).findFirst().orElseThrow().get("presence").toString();}
}
