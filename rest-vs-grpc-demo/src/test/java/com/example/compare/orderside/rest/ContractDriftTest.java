package com.example.compare.orderside.rest;

import com.example.compare.grpc.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tình huống rất hay gặp khi nhiều team cùng làm microservice: team Catalog nâng cấp API,
 * team Order CHƯA kịp cập nhật client. Test này giả lập "Catalog v2" đổi tên field
 * price -> unitPrice và thêm field mới discount, rồi xem client CŨ phản ứng thế nào.
 *
 * Nằm cùng package với RestCatalogClient để dùng đúng DTO ProductJson mà client REST thật đang dùng.
 */
class ContractDriftTest {

    // Cùng cấu hình Jackson mà RestClient/Spring Boot dùng mặc định: BỎ QUA field lạ.
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void rest_renamedField_silentlyBecomesZero() throws Exception {
        String catalogV2Json = """
                {"sku":"SHIRT-01","name":"Áo thun basic","unitPrice":150000,"stock":20,"discount":10}
                """;

        RestCatalogClient.ProductJson parsed = objectMapper.readValue(catalogV2Json, RestCatalogClient.ProductJson.class);

        // KHÔNG có exception nào, nhưng giá = 0 -> đơn hàng được báo giá 0đ. Lỗi âm thầm,
        // chỉ phát hiện khi kế toán đối soát. JSON khớp field theo TÊN, đổi tên là đứt.
        assertThat(parsed.price()).isZero();
        System.out.println("[SO SÁNH] REST client cũ đọc JSON v2 -> " + parsed + "  <-- price = 0 ÂM THẦM");
    }

    @Test
    void grpc_renamedField_stillWorksBecauseFieldNumberUnchanged() throws Exception {
        // Giả lập bytes do "Catalog v2" gửi, với .proto v2:
        //   string sku = 1; string name = 2; int64 unit_price = 3; int32 stock = 4; int32 discount = 5;
        // Tên field KHÔNG hề xuất hiện trên dây - chỉ có SỐ field. Ghi tay bằng CodedOutputStream
        // đúng như code sinh từ .proto v2 sẽ ghi.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(buffer);
        out.writeString(1, "SHIRT-01");
        out.writeString(2, "Áo thun basic");
        out.writeInt64(3, 150_000);  // "unit_price" ở v2, nhưng vẫn là field số 3
        out.writeInt32(4, 20);
        out.writeInt32(5, 10);       // field mới "discount" - client cũ chưa biết
        out.flush();

        // Client CŨ parse bằng class Product sinh từ .proto v1.
        Product parsed = Product.parseFrom(ByteString.copyFrom(buffer.toByteArray()));

        assertThat(parsed.getPrice()).isEqualTo(150_000);          // vẫn đúng
        assertThat(parsed.getUnknownFields().hasField(5)).isTrue(); // field lạ được GIỮ LẠI, không mất
        System.out.println("[SO SÁNH] gRPC client cũ đọc bytes v2 -> price = " + parsed.getPrice()
                + ", field 5 (discount) được giữ nguyên dạng unknown field");
    }
}
