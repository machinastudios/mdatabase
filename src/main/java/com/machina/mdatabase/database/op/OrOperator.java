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

public class OrOperator extends Op<List<Map<String, Object>>> {
    public OrOperator(List<Map<String, Object>> ops) {
        super(OpType.OR, ops);
    }

    public void apply(CriteriaQuery<?> query, CriteriaBuilder cb, Root<?> root, String fieldName, Object value) {
        List<Predicate> predicates = new ArrayList<>();

        for (Map<String, Object> entry : this.value) {
            // Iterate in pairs of key and value
            for (Map.Entry<String, Object> pair : entry.entrySet()) {
                predicates.add(
                    cb.equal(
                        root.get(pair.getKey()),
                        pair.getValue()
                    )
                );
            }
        }

        // Add the or predicate to the query
        query.where(cb.or(predicates.toArray(new Predicate[0])));
    }
}
