-- from copy/csv/pollock/test_field_delimiter.test:5
PRAGMA enable_verification;

-- from copy/csv/pollock/test_field_delimiter.test:8
FROM read_csv('{DATA_DIR}/csv/pollock/file_field_delimiter_0x20.csv', delim = ' ', escape = '"', quote='"', header = false, skip=1,
columns = {'Date':'VARCHAR','TIME':'VARCHAR','Qty':'VARCHAR','PRODUCTID':'VARCHAR','Price':'VARCHAR'
,'ProductType':'VARCHAR','ProductDescription':'VARCHAR','URL':'VARCHAR','Comments':'VARCHAR'}, auto_detect = false, strict_mode=FALSE, null_padding = true);

-- from copy/csv/pollock/test_quotation_char.test:8
FROM read_csv('{DATA_DIR}/csv/pollock/file_quotation_char_0x27.csv', delim = ',', escape = '"', quote='''',
columns = {'Date':'VARCHAR','TIME':'VARCHAR','Qty':'VARCHAR','PRODUCTID':'VARCHAR','Price':'VARCHAR'
,'ProductType':'VARCHAR','ProductDescription':'VARCHAR','URL':'VARCHAR','Comments':'VARCHAR'}, auto_detect = false, strict_mode=FALSE, null_padding = true);
