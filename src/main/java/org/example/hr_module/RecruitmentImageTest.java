package org.example.hr_module;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RecruitmentImageTest {
    static byte[] picture(String format, int width, int height) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,output);
        return output.toByteArray();
    }

    @Test void acceptsAndNormalizesPngAndJpeg() throws Exception {
        for (String format : new String[]{"png","jpeg"}) {
            var content=RecruitmentImage.read(new MockMultipartFile("image","photo."+format,"image/"+format,picture(format,12,8)));
            assertEquals("image/"+format,content.contentType());
            assertEquals(12,ImageIO.read(new java.io.ByteArrayInputStream(content.bytes())).getWidth());
        }
    }
    @Test void checksContentInsteadOfTrustingFileName() {
        assertThrows(ResponseStatusException.class,()->RecruitmentImage.read(
            new MockMultipartFile("image","photo.png","image/png","<svg onload='alert(1)'></svg>".getBytes())));
    }
    @Test void rejectsEmptyAndOversizedFiles() {
        assertThrows(ResponseStatusException.class,()->RecruitmentImage.read(new MockMultipartFile("image",new byte[0])));
        assertThrows(ResponseStatusException.class,()->RecruitmentImage.read(new MockMultipartFile("image",new byte[RecruitmentImage.MAX_BYTES+1])));
    }
    @Test void rejectsUnsupportedImageFormatAndExcessiveDimensions() throws Exception {
        var gif=new MockMultipartFile("image","photo.gif","image/gif",picture("gif",10,10));
        var wide=new MockMultipartFile("image","wide.png","image/png",picture("png",8001,1));
        assertThrows(ResponseStatusException.class,()->RecruitmentImage.read(gif));
        assertThrows(ResponseStatusException.class,()->RecruitmentImage.read(wide));
    }
    @Test void imageIsOptional() { assertNull(RecruitmentImage.read(null)); }
}
