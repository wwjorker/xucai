-- 创建采购目录商品表，不自动清空已有数据。
USE xucai_catalog;
CREATE TABLE IF NOT EXISTS product (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(160) NOT NULL, brand VARCHAR(80) NOT NULL, series VARCHAR(120) NOT NULL,
 category_id INT NOT NULL COMMENT '1显示器 2打印机 3鼠标 4键盘 5摄像头 6耳机',
 image_url VARCHAR(1000) NULL, description TEXT NOT NULL,
 spec_json JSON NOT NULL, spec_version BIGINT NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX idx_product_status_category(status,category_id,id),
 CONSTRAINT ck_product_status CHECK(status IN ('DRAFT','PUBLISHED','HIDDEN')),
 CONSTRAINT ck_product_category CHECK(category_id BETWEEN 1 AND 6),
 CONSTRAINT ck_product_version CHECK(spec_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS product_doc (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, product_id BIGINT NOT NULL,
 doc_key VARCHAR(80) NOT NULL, version BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, source_url VARCHAR(1000) NOT NULL, content MEDIUMTEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_product_doc_version(product_id,doc_key,version),
 INDEX idx_product_doc_status(product_id,status),
 CONSTRAINT fk_doc_product FOREIGN KEY(product_id) REFERENCES product(id),
 CONSTRAINT ck_doc_status CHECK(status IN ('ACTIVE','SUPERSEDED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
USE xucai_trade;
CREATE TABLE IF NOT EXISTS sale_item (
 product_id BIGINT NOT NULL PRIMARY KEY,
 price BIGINT NOT NULL COMMENT '演示售价，人民币分',
 stock INT NOT NULL, on_sale BOOLEAN NOT NULL DEFAULT FALSE,
 sale_version BIGINT NOT NULL DEFAULT 1,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT ck_sale_price CHECK(price BETWEEN 1 AND 100000000),
 CONSTRAINT ck_sale_stock CHECK(stock BETWEEN 0 AND 1000000),
 CONSTRAINT ck_sale_version CHECK(sale_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
