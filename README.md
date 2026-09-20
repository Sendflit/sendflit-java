# com.sendflit:sendflit — Java

Transactional email for applications and AI agents. Zero dependencies (java.net.http, Java 11+).

## Install

Maven:

```xml
<dependency>
  <groupId>com.sendflit</groupId>
  <artifactId>sendflit</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle: `implementation 'com.sendflit:sendflit:1.0.0'`

## Usage

```java
import com.sendflit.SendFlit;

SendFlit sf = SendFlit.builder("re_...").build();

SendFlit.SendResult res = sf.send(SendFlit.Email.builder()
    .from("you@yourdomain.com")
    .to("user@example.com")
    .subject("Confirm your email")
    .html("<p>Click <a href='...'>here</a>.</p>")
    .build());
System.out.println(res.id + " " + res.status);
```

### Attachments / batch / webhook

```java
SendFlit.Email.builder()
    .from("billing@yourdomain.com").to("u@example.com").subject("Receipt")
    .attach("receipt.pdf", pdfBytes, "application/pdf")
    .build();

sf.batch(List.of(emailA, emailB));

SendFlit.verifyWebhookSignature(secret, rawBytes, signatureHeader);
```

Built-in minimal JSON (no Jackson/Gson needed); retries 429/5xx honoring `Retry-After`;
errors throw `SendFlit.SendFlitException` with `.status`/`.body`.

MIT © SendFlit
