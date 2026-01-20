package com.machina.mdatabase.database;

import java.util.Arrays;
import java.util.Map;

import com.machina.mdatabase.database.op.OrOperator;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

public abstract class Op<T> {
    /**
     * Create an OR operation
     * @param ops The operations to OR
     * @return The OR operation
     */
    public static OrOperator or(Map<String, Object> ops) {
        return new OrOperator(ops);
    }

    /**
     * The type of operation
     */
    protected final OpType type;

    /**
     * The value of the operation
     */
    protected final T value;

    public Op(OpType type, T value) {
        this.type = type;
        this.value = value;
    }

    public OpType getType() {
        return type;
    }

    /**
     * Apply the operation to the query
     * @param query The query
     * @param root The root
     * @param cb The criteria builder
     * @param fieldName The field name
     * @param value The value
     */
    public abstract void apply(CriteriaQuery<?> query, CriteriaBuilder cb, Root<?> root, String fieldName, Object value);
}
