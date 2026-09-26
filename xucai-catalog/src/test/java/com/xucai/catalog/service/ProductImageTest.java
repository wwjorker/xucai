package com.xucai.catalog.service;

import com.xucai.common.exceptions.BadRequestException;
import com.xucai.common.exceptions.BusinessException;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ProductImageTest {
    private final String key="products/12345678-1234-1234-1234-123456789abc.png";
    @Test void acceptsRealRasterAndRejectsSvgAndOversize() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",out);
        var image=ProductImageData.decode(out.toByteArray());
        assertEquals("image/png",image.contentType());
        assertEquals(8,ImageIO.read(new java.io.ByteArrayInputStream(image.bytes())).getWidth());
        assertThrows(BadRequestException.class,()->ProductImageData.decode("<svg/>".getBytes()));
        assertThrows(BadRequestException.class,()->ProductImageData.decode(new byte[ProductImageData.MAX_INPUT+1]));
        assertThrows(BadRequestException.class,()->ProductImageData.decode(new byte[0]));
    }
    @Test void stripsTrailingNonImageContent() throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] clean=out.toByteArray();out.write("<script>not retained</script>".getBytes());
        assertArrayEquals(ProductImageData.decode(clean).bytes(),ProductImageData.decode(out.toByteArray()).bytes());
    }
    @Test void referencesCannotTraverseOrSwitchProducts() {
        assertTrue(ProductImageRef.validKey(key));
        assertFalse(ProductImageRef.validKey("products/../secrets.png"));
        assertFalse(ProductImageRef.validKey("https://example.com/a.png"));
        assertNull(ProductImageRef.url(5,"oss:products/../secret"));
        String url=ProductImageRef.url(5,"oss:"+key);
        assertEquals("oss:"+key,ProductImageRef.keepUploaded(5,url,"oss:"+key));
        assertEquals(url,ProductImageRef.keepUploaded(6,url,"oss:"+key));
    }
    @Test void unconfiguredStorageFailsClosed() {
        var store=new ProductImageStore("");
        assertEquals(503,assertThrows(BusinessException.class,()->store.get(key)).getStatus());
        assertEquals(404,assertThrows(BusinessException.class,()->store.get("../other")).getStatus());
    }
}
