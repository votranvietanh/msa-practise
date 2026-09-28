package com.example.compare.orderside;

import java.util.List;

public record OrderQuote(List<Line> lines, long total) {

    public record Line(String sku, String name, int qty, long unitPrice, long lineTotal) {
    }
}
