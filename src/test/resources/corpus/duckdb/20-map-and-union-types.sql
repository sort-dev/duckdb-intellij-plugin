SELECT MAP {'a': 1, 'b': 2} AS literal_map,
       map(['x', 'y'], [10, 20]) AS constructed_map,
       union_value(num := 2) AS tagged_union;
