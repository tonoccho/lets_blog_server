package com.letsblog.content.domain;

/** カスタムタグをMarkdown本文中でどう記述するかの形式。 */
public enum CustomTagFormat {
    /** [tagname]content[/tagname] を文章中に埋め込んで使う形式。 */
    INLINE,
    /** [tagname]\ncontent\n[/tagname] のように複数行のコンテンツを囲む形式。 */
    BLOCK
}
