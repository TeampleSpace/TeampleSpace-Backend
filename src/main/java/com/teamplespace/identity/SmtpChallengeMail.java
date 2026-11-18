package com.teamplespace.identity;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.teamplespace.platform.error.ApiException;
import com.teamplespace.platform.error.ErrorCode;

@Component
public class SmtpChallengeMail implements ChallengeMail {

	private final ObjectProvider<JavaMailSender> sender;
	private final AuthSettings settings;

	public SmtpChallengeMail(ObjectProvider<JavaMailSender> sender, AuthSettings settings) {
		this.sender = sender;
		this.settings = settings;
	}

	public void send(String email, String code) {
		if (!settings.mailEnabled() || sender.getIfAvailable() == null) throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, 60);
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(settings.value("mail-from"));
		message.setTo(email);
		message.setSubject("TeampleSpace 인증 코드");
		message.setText("인증 코드: " + code + "\n5분 안에 입력해 주세요");
		try { sender.getObject().send(message); }
		catch (Exception ex) { throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, 60); }
	}
}
