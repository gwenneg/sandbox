package com.redhat.service.smartevents.infra.api.models.responses;

import java.util.function.Function;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.redhat.service.smartevents.infra.models.ListResult;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "List",
        allOf = { PagedListResponse.class, ListResponse.class })
public abstract class PagedListResponse<T> extends ListResponse<T> {

    public static <T, V> PagedListResponse<V> fill(ListResult<T> source, PagedListResponse<V> target, Function<T, V> converter) {
        target.setItems(source.getItems().stream().map(converter).collect(Collectors.toList()));
        target.setPage(source.getPage());
        target.setSize(source.getSize());
        target.setTotal(source.getTotal());
        return target;
    }

    protected PagedListResponse(String kind) {
        super(kind);
    }

    @NotNull
    @JsonProperty("page")
    private long page;

    @NotNull
    @JsonProperty("size")
    private long size;

    @NotNull
    @JsonProperty("total")
    private long total;

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
