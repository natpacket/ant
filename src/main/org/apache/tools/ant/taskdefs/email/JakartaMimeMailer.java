/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.apache.tools.ant.taskdefs.email;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Address;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;


/**
 * Uses the JakartaMail classes to send Mime format email.
 *
 * @since Ant 1.10.13
 */
public class JakartaMimeMailer extends Mailer {
    private static final String SSL_FACTORY = "javax.net.ssl.SSLSocketFactory";

    private static final String GENERIC_ERROR =
        "Problem while sending mime mail:";

    /** Default character set */
    private static final String DEFAULT_CHARSET
        = System.getProperty("file.encoding");

    // To work properly with national charsets we have to use
    // implementation of interface jakarta.activation.DataSource
    /**
     * String data source implementation.
     */
    class StringDataSource implements DataSource {
        private String data = null;
        private String type = null;
        private String charset = null;
        private ByteArrayOutputStream out;

        @Override
        public InputStream getInputStream() throws IOException {
            if (data == null && out == null) {
                throw new IOException("No data");
            }
            if (out != null) {
                final String encodedOut = out.toString(charset);
                data = (data != null) ? data.concat(encodedOut) : encodedOut;
                out = null;
            }
            return new ByteArrayInputStream(data.getBytes(charset));
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            out = (out == null) ? new ByteArrayOutputStream() : out;
            return out;
        }

        public void setContentType(final String type) {
            this.type = type.toLowerCase(Locale.ENGLISH);
        }

        @Override
        public String getContentType() {
            if (type != null && type.indexOf("charset") > 0
                && type.startsWith("text/")) {
                return type;
            }
            // Must be like "text/plain; charset=windows-1251"
            return (type != null ? type : "text/plain") +
                    "; charset=" + charset;
        }

        @Override
        public String getName() {
            return "StringDataSource";
        }

        public void setCharset(final String charset) {
            this.charset = charset;
        }

        public String getCharset() {
            return charset;
        }
    }

    /**
     * Send the email.
     *
     * @throws BuildException if the email can't be sent.
     */
    @Override
    public void send() {
        try {
            final Properties props = new Properties();

            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));

            // Aside, the JDK is clearly unaware of the Scottish
            // 'session', which involves excessive quantities of
            // alcohol :-)
            Session sesh;
            Authenticator auth = null;
            if (SSL) {
                // SMTP provider
                props.put("mail.smtp.socketFactory.class", SSL_FACTORY);
                props.put("mail.smtp.socketFactory.fallback", "false");
                props.put("mail.smtps.host", host);
                if (isPortExplicitlySpecified()) {
                    props.put("mail.smtps.port", String.valueOf(port));
                    props.put("mail.smtp.socketFactory.port",
                              String.valueOf(port));
                }
                if (shouldCheckServerIdentity()) {
                    props.put("mail.smtp.ssl.checkserveridentity", "true");
                }
            }
            if (user != null || password != null) {
                props.put("mail.smtp.auth", "true");
                auth = new SimpleAuthenticator(user, password);
            }
            if (isStartTLSEnabled()) {
                props.put("mail.smtp.starttls.enable", "true");
                if (isStartTLSRequired()) {
                    props.put("mail.smtp.starttls.required", "true");
                }
                if (shouldCheckServerIdentity()) {
                    props.put("mail.smtp.ssl.checkserveridentity", "true");
                }
            }
            sesh = Session.getInstance(props, auth);

            //create the message
            final MimeMessage msg = new MimeMessage(sesh);
            final MimeMultipart attachments = new MimeMultipart();

            //set the sender
            if (from.getName() == null) {
                msg.setFrom(new InternetAddress(from.getAddress()));
            } else {
                msg.setFrom(new InternetAddress(from.getAddress(),
                    from.getName()));
            }
            // set the reply to addresses
            msg.setReplyTo(internetAddresses(replyToList));
            msg.setRecipients(Message.RecipientType.TO,
                internetAddresses(toList));
            msg.setRecipients(Message.RecipientType.CC,
                internetAddresses(ccList));
            msg.setRecipients(Message.RecipientType.BCC,
                internetAddresses(bccList));

            // Choosing character set of the mail message
            // First: looking it from MimeType
            String charset = parseCharSetFromMimeType(message.getMimeType());
            if (charset != null) {
                // Assign/reassign message charset from MimeType
                message.setCharset(charset);
            } else {
                // Next: looking if charset having explicit definition
                charset = message.getCharset();
                if (charset == null) {
                    // Using default
                    charset = DEFAULT_CHARSET;
                    message.setCharset(charset);
                }
            }
            // Using jakarta.activation.DataSource paradigm
            final StringDataSource sds = new StringDataSource();
            sds.setContentType(message.getMimeType());
            sds.setCharset(charset);

            if (subject != null) {
                msg.setSubject(subject, charset);
            }
            msg.addHeader("Date", getDate());

            if (headers != null) {
                for (Header h : headers) {
                    msg.addHeader(h.getName(), h.getValue());
                }
            }
            final PrintStream out = new PrintStream(sds.getOutputStream());
            message.print(out);
            out.close();

            final MimeBodyPart textbody = new MimeBodyPart();
            textbody.setDataHandler(new DataHandler(sds));
            attachments.addBodyPart(textbody);

            for (File file : files) {
                MimeBodyPart body = new MimeBodyPart();
                if (!file.exists() || !file.canRead()) {
                    throw new BuildException(
                        "File \"%s\" does not exist or is not readable.",
                        file.getAbsolutePath());
                }
                final FileDataSource fileData = new FileDataSource(file);
                final DataHandler fileDataHandler = new DataHandler(fileData);

                body.setDataHandler(fileDataHandler);
                body.setFileName(file.getName());
                attachments.addBodyPart(body);
            }
            msg.setContent(attachments);
            try {
                // Send the message using SMTP, or SMTPS if the host uses SSL
                final Transport transport = sesh.getTransport(SSL ? "smtps" : "smtp");
                transport.connect(host, user, password);
                transport.sendMessage(msg, msg.getAllRecipients());
            } catch (final SendFailedException sfe) {
                if (!shouldIgnoreInvalidRecipients()) {
                    throw new BuildException(GENERIC_ERROR, sfe);
                }
                if (sfe.getValidSentAddresses() == null
                           || sfe.getValidSentAddresses().length == 0) {
                    throw new BuildException("Couldn't reach any recipient",
                                             sfe);
                }
                Address[] invalid = sfe.getInvalidAddresses();
                if (invalid == null) {
                    invalid = new Address[0];
                }
                for (Address address : invalid) {
                    didntReach(address, "invalid", sfe);
                }
                Address[] validUnsent = sfe.getValidUnsentAddresses();
                if (validUnsent == null) {
                    validUnsent = new Address[0];
                }
                for (Address address : validUnsent) {
                    didntReach(address, "valid", sfe);
                }
            }
        } catch (MessagingException | IOException e) {
            throw new BuildException(GENERIC_ERROR, e);
        }
    }

    private static InternetAddress[] internetAddresses(final Vector<EmailAddress> list)
        throws AddressException, UnsupportedEncodingException {

        final List<InternetAddress> addrs = new ArrayList<>();

        for (final EmailAddress addr : list) {
            final String name = addr.getName();
            addrs.add((name == null)
                ? new InternetAddress(addr.getAddress())
                : new InternetAddress(addr.getAddress(), name));
        }
        return addrs.toArray(new InternetAddress[addrs.size()]);
    }

    private String parseCharSetFromMimeType(final String type) {
        if (type == null) {
            return null;
        }
        final int pos = type.indexOf("charset");
        if (pos < 0) {
            return null;
        }
        // Assuming mime type in form "text/XXXX; charset=XXXXXX"
        final StringTokenizer token = new StringTokenizer(type.substring(pos), "=; ");
        token.nextToken(); // Skip 'charset='
        return token.nextToken();
    }

    private void didntReach(final Address addr, final String category,
                            final MessagingException ex) {
        final String msg = "Failed to send mail to " + category + " address "
            + addr + " because of " + ex.getMessage();
        if (task != null) {
            task.log(msg, Project.MSG_WARN);
        } else {
            System.err.println(msg);
        }
    }

    static class SimpleAuthenticator extends Authenticator {
        private String user = null;
        private String password = null;

        public SimpleAuthenticator(final String user, final String password) {
            this.user = user;
            this.password = password;
        }

        @Override
        public PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(user, password);
        }
    }
}

