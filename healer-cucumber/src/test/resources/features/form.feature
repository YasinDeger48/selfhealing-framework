Feature: Contact form

  Scenario: Save after a rename
    Given the form is open
    When the save button gets a new id
    And I press save
    Then the form says "Saved"

  Scenario: Wrong expectation
    Given the form is open
    Then the cancel button says "Abort"
