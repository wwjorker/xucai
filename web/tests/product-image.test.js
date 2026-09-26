import test from "node:test";
import assert from "node:assert/strict";
import { productImageUrl, safeUrl } from "../src/domain.js";

test("product images allow only exact application paths or safe HTTP URLs", () => {
  const path="/api/catalog/products/36/image/12345678-1234-1234-1234-123456789abc.png";
  assert.equal(productImageUrl(path),path);
  assert.equal(safeUrl(path),null);
  for(const invalid of ["//evil.test/image",path+"?redirect=evil",path+"/../secret","/api/catalog/admin/products/1/image/a.png","data:image/svg+xml,<svg/>","https://user:secret@evil.test/image"])
    assert.equal(productImageUrl(invalid),null);
  assert.equal(productImageUrl("https://example.com/p.png"),"https://example.com/p.png");
});
