from io import BytesIO

import pytest
from pypdf import PdfWriter

from app.rag.document_parser import DocumentParseError, parse_pdf_pages
from app.services.personal_pdf_tutor import PdfPage, answer_personal_pdf


def test_chinese_question_cites_the_original_page_and_declines_unrelated_topics():
    pages = [
        PdfPage(number=1, text="栈遵循后进先出原则。"),
        PdfPage(number=3, text="斐波那契递推用前两项的和得到下一项。"),
    ]
    answer = answer_personal_pdf("请问斐波那契递推是什么？", pages)
    assert answer.outcome == "answered"
    assert [(item.page, item.excerpt) for item in answer.evidence] == [
        (3, "斐波那契递推用前两项的和得到下一项。"),
    ]
    unrelated = answer_personal_pdf("海王星的轨道半径是多少？", pages)
    assert unrelated.outcome == "insufficient_evidence"
    assert unrelated.evidence == []


def test_personal_excerpts_receive_the_safety_review():
    answer = answer_personal_pdf("作弊规则", [PdfPage(number=2, text="这句话包含作弊规则。")])
    assert answer.outcome == "review_required"
    assert answer.evidence == []


@pytest.mark.parametrize("encrypted, max_pages, expected", [
    (False, 100, "no_text"),
    (True, 100, "encrypted"),
    (False, 1, "page_limit"),
])
def test_parser_explains_why_a_document_cannot_be_uploaded(encrypted, max_pages, expected):
    writer = PdfWriter()
    for _ in range(2):
        writer.add_blank_page(500, 500)
    if encrypted:
        writer.encrypt("password")
    pdf = BytesIO()
    writer.write(pdf)
    with pytest.raises(DocumentParseError, match=expected):
        parse_pdf_pages(pdf.getvalue(), max_pages=max_pages, max_characters=1000)
