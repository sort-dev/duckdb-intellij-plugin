-- from types/variant/implicit_cast_from_variant.test:4
select [100::VARIANT, 1.2];

-- from types/variant/implicit_cast_from_variant.test:10
select ['test', 'hello', 'world'][1::VARIANT::INTEGER];

-- from types/variant/implicit_cast_from_variant.test:16
select ['test', 'hello', 'world'][1::VARIANT];

-- from types/variant/implicit_cast_from_variant.test:22
select ['test', 'hello', 'world'][1::BIGINT::VARIANT];
