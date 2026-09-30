package com.letsblog.media.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * 任意の位置({@code offset})から最大{@code limit}件を取り出す{@link Pageable}(issue #1472)。
 *
 * <p>Spring Dataの{@code PageRequest}はページ番号×ページサイズでしか位置を表せず、
 * {@code offset}がページサイズの倍数でないと指定できない。{@code GET /api/generated-images}の
 * limit/offsetは任意の組み合わせを受けるため、オフセットをそのまま持つ実装を使う。
 * 並び順はリポジトリのメソッド名({@code OrderBy...})が決めるので、ここでは持たない。
 */
public record OffsetLimitPageable(long offset, int limit) implements Pageable {

    public OffsetLimitPageable {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetLimitPageable(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetLimitPageable(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetLimitPageable(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimitPageable((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
