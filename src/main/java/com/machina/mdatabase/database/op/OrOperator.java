package com.machina.mdatabase.database.op;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.machina.mdatabase.database.Op;
import com.machina.mdatabase.database.OpType;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

public class OrOperator extends Op<Map<String, Object>> {
    public OrOperator(Map<String, Object> ops) {
        super(OpType.OR, ops);
    }

    public void apply(CriteriaQuery<?> query, CriteriaBuilder cb, Root<?> root, String fieldName, Object value) {
        List<Predicate> predicates = new ArrayList<>();

        // Iterate in pairs of key and value
        for (Map.Entry<String, Object> entry : this.value.entrySet()) {
            // If the value is null, skip it
            if (entry.getValue() == null) {
                continue;
            }

            // Add the predicate to the list
            predicates.add(
                cb.equal(
                    root.get(entry.getKey()),
                    entry.getValue()
                )
            );
        }

        // Add the or predicate to the query
        query.where(cb.or(predicates.toArray(new Predicate[0])));
    }
}
