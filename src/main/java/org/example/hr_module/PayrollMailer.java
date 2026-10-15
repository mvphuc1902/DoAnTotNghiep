package org.example.hr_module;

import java.util.Properties;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

@Component
public class PayrollMailer {
    private final Environment env;
    public PayrollMailer(Environment env) { this.env=env; }
    private String setting(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return env.getProperty("ERP_MAIL_" + key, env.getProperty("erp.mail." + normalized, "")).trim();
    }
    public String sender() { return setting("FROM"); }
    public boolean configured() { return configurationProblem().isEmpty(); }
    public String configurationProblem() {
        for (String key : new String[]{"HOST", "FROM", "USERNAME", "PASSWORD"}) {
            if (setting(key).isEmpty()) {
                return key.equals("PASSWORD")
                    ? "Chưa cấu hình mật khẩu gửi email. Quản trị viên cần cấu hình thông tin xác thực SMTP và khởi động lại ứng dụng."
                    : "Cấu hình máy chủ gửi email chưa đầy đủ. Vui lòng liên hệ quản trị viên.";
            }
        }
        try { address(sender()); }
        catch (IllegalArgumentException error) { return "Địa chỉ email người gửi không hợp lệ. Vui lòng liên hệ quản trị viên."; }
        try {
            String value = setting("PORT");
            int port = value.isEmpty() ? 587 : Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException error) { return "Cổng máy chủ gửi email không hợp lệ. Vui lòng liên hệ quản trị viên."; }
        String ssl = setting("SSL");
        if (!ssl.isEmpty() && !ssl.equalsIgnoreCase("true") && !ssl.equalsIgnoreCase("false"))
            return "Cấu hình bảo mật email không hợp lệ. Vui lòng liên hệ quản trị viên.";
        return "";
    }
    static InternetAddress address(String value) {
        try {
            if(value==null || value.contains("\r") || value.contains("\n")) throw new IllegalArgumentException();
            InternetAddress[] addresses=InternetAddress.parse(value,true);
            if(addresses.length!=1 || addresses[0].isGroup() || !addresses[0].getAddress().equals(value.trim()) || !value.contains("@")) throw new IllegalArgumentException();
            addresses[0].validate(); return addresses[0];
        } catch(Exception e) { throw new IllegalArgumentException("Set a valid single email address on the employee's user account."); }
    }
    static MimeMultipart content(String text,String html) throws MessagingException {
        MimeMultipart alternative=new MimeMultipart("alternative");
        MimeBodyPart plain=new MimeBodyPart();plain.setText(text,"UTF-8");alternative.addBodyPart(plain);
        MimeBodyPart report=new MimeBodyPart();report.setText(html,"UTF-8","html");alternative.addBodyPart(report);
        return alternative;
    }
    public void send(String recipient,String subject,String text,String html) throws MessagingException {
        String problem = configurationProblem();
        if (!problem.isEmpty()) throw new MessagingException(problem);
        Properties props=new Properties();
        props.setProperty("mail.smtp.host",setting("HOST"));
        props.setProperty("mail.smtp.port",setting("PORT").isEmpty() ? "587" : setting("PORT"));
        props.setProperty("mail.smtp.auth","true");
        boolean ssl=Boolean.parseBoolean(setting("SSL"));
        props.setProperty("mail.smtp.ssl.enable",String.valueOf(ssl));
        props.setProperty("mail.smtp.starttls.enable",String.valueOf(!ssl));
        props.setProperty("mail.smtp.starttls.required",String.valueOf(!ssl));
        props.setProperty("mail.smtp.ssl.checkserveridentity","true");
        props.setProperty("mail.smtp.connectiontimeout","10000");
        props.setProperty("mail.smtp.timeout","15000");
        props.setProperty("mail.smtp.writetimeout","15000");
        Session session=Session.getInstance(props,new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(setting("USERNAME"), setting("PASSWORD"));
            }
        });
        MimeMessage message=new MimeMessage(session);
        message.setFrom(address(setting("FROM")));
        message.setRecipient(Message.RecipientType.TO,address(recipient));
        message.setSubject(subject,"UTF-8"); message.setContent(content(text,html));
        Transport.send(message);
    }
}
