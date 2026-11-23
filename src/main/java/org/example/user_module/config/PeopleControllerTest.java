package org.example.user_module.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PeopleControllerTest {
 static MockMultipartFile photo(int color) throws Exception {var image=new BufferedImage(400,300,BufferedImage.TYPE_INT_RGB);for(int x=0;x<400;x++)for(int y=0;y<300;y++)image.setRGB(x,y,color);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return new MockMultipartFile("image","photo.png","image/png",out.toByteArray());}
 @Test void normalizesAvatarAndKeepsDifferentAccountsImagesDistinct() throws Exception {var first=PeopleController.readImage(photo(0xff0000));var second=PeopleController.readImage(photo(0x0000ff));var image=ImageIO.read(new ByteArrayInputStream(first));assertEquals(256,image.getWidth());assertEquals(256,image.getHeight());assertFalse(java.util.Arrays.equals(first,second));}
 @Test void rejectsInvalidAndOversizedImages(){assertThrows(ResponseStatusException.class,()->PeopleController.readImage(new MockMultipartFile("image","x.png","image/png","<svg/>".getBytes())));assertThrows(ResponseStatusException.class,()->PeopleController.readImage(new MockMultipartFile("image",new byte[2097153])));}
 @Test void cannotChangeOtherAccountsAvatar() throws Exception {var db=mock(JdbcTemplate.class);when(db.queryForObject("SELECT id FROM users WHERE lower(username)=lower(?)",Long.class,"alice")).thenReturn(1L);var auth=new UsernamePasswordAuthenticationToken("alice","",List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")));assertEquals(403,assertThrows(ResponseStatusException.class,()->new PeopleController(db).upload(auth,2L,photo(0xff0000))).getStatusCode().value());verify(db,never()).update(anyString(),any(Object[].class));}
 @Test void sessionIdentifiersAreHashedSeparately(){assertEquals(64,PeopleController.sessionKey("one").length());assertNotEquals(PeopleController.sessionKey("one"),PeopleController.sessionKey("two"));}
}
