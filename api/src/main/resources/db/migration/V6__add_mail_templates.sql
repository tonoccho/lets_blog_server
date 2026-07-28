CREATE TABLE mail_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_key VARCHAR(100) NOT NULL UNIQUE,
    subject VARCHAR(255) NOT NULL,
    body_html TEXT NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO mail_templates (template_key, subject, body_html, description, is_active) VALUES (
    'password-reset',
    'Let''s Blog - パスワード再設定',
    '<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<style>
body { font-family: Arial, sans-serif; background-color: #f5f5f5; }
.container { max-width: 600px; margin: 20px auto; background: #ffffff; padding: 20px; border-radius: 5px; }
.header { background-color: #2c3e50; color: #ffffff; padding: 20px; text-align: center; }
.content { padding: 20px; line-height: 1.6; }
.cta { text-align: center; margin: 20px 0; }
.button { display: inline-block; padding: 10px 20px; background-color: #3498db; color: #ffffff; text-decoration: none; border-radius: 5px; }
.footer { background-color: #ecf0f1; padding: 10px; text-align: center; font-size: 12px; color: #7f8c8d; }
</style>
</head>
<body>
<div class="container">
<div class="header"><h1>Let''s Blog</h1></div>
<div class="content">
<p>パスワードをリセットするリクエストを受け取りました。</p>
<p>以下のボタンをクリックして、パスワードをリセットしてください。このリンクは24時間有効です。</p>
<div class="cta"><a href="[[${resetLink}]]" class="button">パスワードをリセット</a></div>
<p>ご自身でリクエストしていない場合は、このメールを無視してください。</p>
</div>
<div class="footer"><p>&copy; Let''s Blog. All rights reserved.</p></div>
</div>
</body>
</html>',
    'パスワード再設定リンクの案内メール',
    TRUE
);
