package org.apache.logging.log4j.catalog.controller;

import javax.annotation.PostConstruct;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.catalog.api.Category;
import org.apache.logging.log4j.catalog.jpa.model.CategoryModel;
import org.apache.logging.log4j.catalog.jpa.service.CategoryService;
import org.apache.logging.log4j.catalog.jpa.converter.CategoryConverter;
import org.apache.logging.log4j.catalog.jpa.converter.CategoryModelConverter;
import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog Category controller
 */

@RequestMapping(value = "/api/categories")
@RestController
public class CategoryController {
    private static final Logger LOGGER = LogManager.getLogger();

    private ModelMapper modelMapper = new ModelMapper();

    @Autowired
    private CategoryService categoryService;


    @Autowired
    private CategoryModelConverter categoryModelConverter;

    @Autowired
    private CategoryConverter categoryConverter;

    @PostConstruct
    public void init() {
        modelMapper.addConverter(categoryModelConverter);
    }

    @PostMapping(value = "/list")
    public ResponseEntity<Map<String, Object>> categoryList(@RequestParam(value="jtStartIndex", required=false) Integer startIndex,
                                                            @RequestParam(value="jtPageSize", required=false) Integer pageSize,
                                                            @RequestParam(value="jtSorting", required=false) String sorting) {
        Type listType = new TypeToken<List<Category>>() {}.getType();
        Map<String, Object> response = new HashMap<>();
        try {
            List<Category> categories = null;
            if (startIndex == null || pageSize == null) {
                categories = modelMapper.map(categoryService.getCategories(), listType);
            } else {
                int startPage = 0;
                if (startIndex > 0) {
                    startPage = startIndex / pageSize;
                }
                String sortColumn = "name";
                String sortDirection = "ASC";
                if (sorting != null) {
                    String[] sortInfo = sorting.split(" ");
                    sortColumn = sortInfo[0];
                    if (sortInfo.length > 0) {
                        sortDirection = sortInfo[1];
                    }
                }
                categories = modelMapper.map(categoryService.getCategories(startPage, pageSize, sortColumn, sortDirection), listType);
            }
            if (categories == null) {
                categories = new ArrayList<>();
            }
            response.put("Result", "OK");
            response.put("Records", categories);
        } catch (Exception ex) {
            response.put("Result", "FAILURE");
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping(value = "/create")
    public ResponseEntity<Map<String, Object>> createCategory(@RequestBody Category category) {
        Map<String, Object> response = new HashMap<>();
        try {
            CategoryModel model = categoryConverter.convert(category);
            model = categoryService.saveCategory(model);
            response.put("Result", "OK");
            response.put("Records", categoryModelConverter.convert(model));
        } catch (Exception ex) {
            response.put("Result", "FAILURE");
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping(value = "/update")
    public ResponseEntity<Map<String, Object>> updateCategory(@RequestBody Category category) {
        Map<String, Object> response = new HashMap<>();
        try {
            CategoryModel model = categoryConverter.convert(category);
            model = categoryService.saveCategory(model);
            response.put("Result", "OK");
            response.put("Records", categoryModelConverter.convert(model));
        } catch (Exception ex) {
            response.put("Result", "FAILURE");
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @PostMapping(value = "/delete")
    public ResponseEntity<Map<String, Object>> deleteCategory(@RequestBody Category category) {
        Map<String, Object> response = new HashMap<>();
        try {
            categoryService.deleteCategory(category.getId());
            response.put("Result", "OK");
        } catch (Exception ex) {
            response.put("Result", "FAILURE");
        }
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}
