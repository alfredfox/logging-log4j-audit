$(document).ready(function () {
    // Clear localStorage
    localStorage.clear();

    $('#save-all').click(function(e) {
      e.preventDefault();
      saveAllChanges();
    });
});

// Modal action handlers
function closeLog4jModal() {
    $('.log4j-catalog-modal').remove();
}

function log4jSubmitHandler(submitHandler) {
    submitHandler();
    closeLog4jModal();
}

function showLog4JModal(title, content) {
    closeLog4jModal();
    var modalContent = ' \
        <div class="log4j-catalog-modal"> \
            <div class="log4j-catalog-title">' + title + '</div> \
            <div class="log4j-catalog-content">' + content + '</div> \
        </div>';

    $('body').append(modalContent);
    window.scrollTo(0, 0);
}

function showLoadingAnimation() {
  $('.log4j-catalog-form').prepend('<div class="form-processing"><div class="gif"></div></div>');
}

function validateFormContent() {
    var errors = 0;
    $('.form-error').remove();
    $('.required').each(function() {
        if (!$(this).val()) {
          errors++;
          $('<span class="form-error">Required.</span>').insertAfter($(this));
        }
    });
    if (errors) return false;
    return true;
}

function saveAllChanges() {
  $.ajax({
      type: 'POST',
      contentType: 'application/json',
      url: 'catalog',
      data: null,
      success:function(response) {
          if (response.Result === 'OK') {
              $('.log4j-table-container"').jtable('load');
          }
      },
      error:function(jqXhr, textStatus, errorThrown) {
          console.error(textStatus + ' - ' + errorThrown);
      }
  });
}
