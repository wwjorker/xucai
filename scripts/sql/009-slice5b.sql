-- 切片5b：只在采购目录库新增派生片段与索引表。正文事实源仍是 product_doc.content，
-- 本文件不修改 product/product_doc 的任何一行。
-- 失败恢复：两条 CREATE 各自独立，中断后重新执行即可（IF NOT EXISTS 幂等）；
-- 片段可由 POST /admin/products/{id}/docs/{docId}/reindex 随时整版重建，无需手工补数据。
USE xucai_catalog;
CREATE TABLE IF NOT EXISTS product_doc_index (
 doc_id BIGINT NOT NULL PRIMARY KEY COMMENT 'product_doc.id；一行资料就是一个版本，整版重建',
 product_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 content_hash CHAR(64) NOT NULL COMMENT '建索引时正文的SHA-256；与当前正文不符即判定过期',
 content_length INT NOT NULL,
 chunk_count INT NOT NULL,
 rule_version VARCHAR(20) NOT NULL COMMENT '切分规则版本；规则变化必须整版重建',
 status VARCHAR(16) NOT NULL COMMENT 'READY可用；FAILED表示切分失败，正文仍然保留',
 error_message VARCHAR(300) NULL,
 built_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 KEY idx_doc_index_product(product_id,doc_id),
 CONSTRAINT fk_doc_index_doc FOREIGN KEY(doc_id) REFERENCES product_doc(id),
 CONSTRAINT ck_doc_index_status CHECK(status IN ('READY','FAILED')),
 CONSTRAINT ck_doc_index_count CHECK(chunk_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS product_doc_chunk (
 chunk_id VARCHAR(80) NOT NULL PRIMARY KEY COMMENT '稳定ID doc:{docId}:v{version}:c{n}',
 doc_id BIGINT NOT NULL,
 product_id BIGINT NOT NULL,
 version BIGINT NOT NULL,
 chunk_no INT NOT NULL,
 char_start INT NOT NULL COMMENT '在 product_doc.content 中的起始下标（Java字符计）',
 char_end INT NOT NULL,
 heading_path VARCHAR(400) NOT NULL DEFAULT '',
 text MEDIUMTEXT NOT NULL COMMENT '派生副本，只用于查找与显示；不可编辑，以正文为准',
 text_length INT NOT NULL,
 oversize BOOLEAN NOT NULL DEFAULT FALSE COMMENT '单句超长仍整句保留，不在句中切开',
 UNIQUE KEY uk_doc_chunk_no(doc_id,chunk_no),
 KEY idx_doc_chunk_product(product_id,doc_id,chunk_no),
 CONSTRAINT fk_doc_chunk_index FOREIGN KEY(doc_id) REFERENCES product_doc_index(doc_id),
 CONSTRAINT ck_doc_chunk_range CHECK(char_end > char_start AND char_start >= 0),
 CONSTRAINT ck_doc_chunk_no CHECK(chunk_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
