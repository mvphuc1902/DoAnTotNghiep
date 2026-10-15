package org.example.hr_module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class RecruitmentImage {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    record Content(byte[] bytes, String contentType) {}

    static Content read(MultipartFile file) {
        if (file == null) return null;
        if (file.isEmpty()) throw invalid("Ảnh tải lên đang trống.");
        if (file.getSize() > MAX_BYTES) throw invalid("Ảnh không được vượt quá 5 MB.");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(file.getBytes()))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid("Vui lòng chọn ảnh JPG hoặc PNG hợp lệ.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw invalid("Chỉ hỗ trợ ảnh JPG và PNG.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 8000 || height > 8000 || (long) width * height > 16_000_000)
                    throw invalid("Ảnh tối đa 16 triệu điểm ảnh và 8000 px mỗi chiều.");
                var decoded = reader.read(0);
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(decoded, format, output)) throw invalid("Không thể xử lý ảnh này.");
                if (output.size() > MAX_BYTES) throw invalid("Ảnh sau xử lý vượt 5 MB. Vui lòng giảm kích thước ảnh.");
                return new Content(output.toByteArray(), "image/" + format);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid("Không đọc được ảnh. Vui lòng chọn ảnh JPG hoặc PNG khác.");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
