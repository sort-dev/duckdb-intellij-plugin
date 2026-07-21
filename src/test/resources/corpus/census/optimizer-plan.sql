-- from optimizer/plan/test_disable_build_side_probe_side.test:5
pragma explain_output='optimized_only';

-- from optimizer/plan/test_disable_build_side_probe_side.test:8
set disabled_optimizers to 'build_side_probe_side';

-- from optimizer/plan/test_disable_build_side_probe_side.test:11
explain from range(10) r1 right join range(10) r2 using (range);

-- from optimizer/plan/test_filter_pushdown.test:5
SET default_null_order='nulls_first';
