package com.machina.mdatabase.database;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Options for find queries (similar to Sequelize)
 * Used with findAll() and findOne() methods
 */
public class FindOptions {
    /**
     * Where conditions (field name -> value)
     */
    public Map<String, Object> where;

    /**
     * Attributes to select (field names)
     * If null, selects all fields
     */
    public List<String> attributes;

    /**
     * Limit the number of results
     */
    public Integer limit;

    /**
     * Skip/offset the number of results
     */
    public Integer skip;

    /**
     * Constructor with no options
     */
    public FindOptions() {
        this.where = new HashMap<>();
    }

    /**
     * Constructor with where conditions
     * @param where Where conditions
     */
    public FindOptions(Op<?> where) {
        this.where = Map.of("$", where);
    }

    /**
     * Constructor with where conditions
     * @param where Where conditions
     */
    public FindOptions(Map<String, Object> where) {
        this.where = where != null ? where : new HashMap<>();
    }

    /**
     * Create FindOptions with where conditions
     * @param where Where conditions
     * @return FindOptions instance
     */
    public static FindOptions where(Map<String, Object> where) {
        return new FindOptions(where);
    }

    /**
     * Create FindOptions with where conditions
     * @param where Where conditions
     * @return FindOptions instance
     */
    public static FindOptions where(Op<?> where) {
        return new FindOptions(where);
    }

    /**
     * Create FindOptions with where conditions (single field)
     * @param field Field name
     * @param value Field value
     * @return FindOptions instance
     */
    public static FindOptions where(String field, Object value) {
        Map<String, Object> where = new HashMap<>();
        where.put(field, value);
        return new FindOptions(where);
    }

    /**
     * Set attributes to select
     * @param attributes List of field names
     * @return This instance for chaining
     */
    public FindOptions attributes(List<String> attributes) {
        this.attributes = attributes;
        return this;
    }

    /**
     * Set limit
     * @param limit Limit value
     * @return This instance for chaining
     */
    public FindOptions limit(Integer limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Set skip/offset
     * @param skip Skip value
     * @return This instance for chaining
     */
    public FindOptions skip(Integer skip) {
        this.skip = skip;
        return this;
    }
}
