package com.xucai.catalog.controller;

import com.xucai.common.annotations.NoWrapper;
import com.xucai.common.utils.MallIdentity;
import com.xucai.common.exceptions.BadRequestException;
import com.xucai.catalog.service.*;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController @Validated @RequiredArgsConstructor
public class ProductImageController {
    private final ProductService products;
    private final ProductImageStore storage;

    @PostMapping(value="/admin/products/{id}/image", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@PathVariable @Positive long id, @RequestParam @Positive long expectedSpecVersion,
                                     @RequestPart("file") MultipartFile file) throws IOException {
        MallIdentity.merchant();
        products.checkImageUpload(id,expectedSpecVersion);
        if(file.isEmpty() || file.getSize()>ProductImageData.MAX_INPUT) throw new BadRequestException("请选择不超过2MB的JPEG或PNG图片");
        ProductImageData image = ProductImageData.decode(file.getBytes());
        String key = storage.put(image);
        // Object uploads cannot join a DB transaction. A rejected association leaves an unreferenced private object.
        var state = products.attachImage(id,expectedSpecVersion,key);
        return Map.of("specVersion",state.specVersion(),"imageUrl",ProductImageRef.url(id,"oss:"+key));
    }
    @NoWrapper @GetMapping("/products/{id}/image/{file}")
    public ResponseEntity<byte[]> image(@PathVariable @Positive long id, @PathVariable String file) {
        return read(id,file,false);
    }
    @NoWrapper @GetMapping("/admin/products/{id}/image/{file}")
    public ResponseEntity<byte[]> preview(@PathVariable @Positive long id, @PathVariable String file) {
        MallIdentity.merchant();
        return read(id,file,true);
    }
    private ResponseEntity<byte[]> read(long id,String file,boolean admin) {
        String key = products.imageKey(id,file,admin);
        var image = storage.get(key);
        products.imageKey(id,file,admin); // Recheck visibility/association after storage IO.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .contentLength(image.bytes().length).body(image.bytes());
    }
}
