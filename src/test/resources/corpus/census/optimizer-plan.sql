-- from optimizer/plan/test_anti_join_empty_child.test:5
pragma explain_output='OPTIMIZED_ONLY';

-- from optimizer/plan/test_anti_join_empty_child.test:8
SELECT lhs.id FROM (SELECT 1 id) lhs ANTI JOIN (SELECT 1 id WHERE FALSE) rhs ON lhs.id = rhs.id;

-- from optimizer/plan/test_anti_join_empty_child.test:13
EXPLAIN SELECT lhs.id FROM (SELECT 1 id) lhs ANTI JOIN (SELECT 1 id WHERE FALSE) rhs ON lhs.id = rhs.id;

-- from optimizer/plan/test_disable_build_side_probe_side.test:5
pragma explain_output='optimized_only';
